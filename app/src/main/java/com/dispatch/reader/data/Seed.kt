package com.dispatch.reader.data

import android.content.Context
import com.dispatch.reader.util.Prefs
import com.dispatch.reader.util.Safely

/**
 * The first run: a hundred feeds, filed under seven categories, and **no
 * streams at all**.
 *
 * That last part is the point. The bundled set is a *library* — raw material
 * for the reader to build streams out of — not a set of seven ready-made
 * sections to live inside. 1.0.x created a stream per category and the effect
 * was exactly backwards: it looked like the app's opinion of how news should be
 * divided, and the reader's own streams were the afterthought.
 *
 * So the categories are filing labels, and their whole job is to make a hundred
 * feeds findable in the stream builder's search. The app opens on All feeds
 * with a card offering to build the first stream.
 *
 * The bundled set is an OPML file in `assets/`, which means it is the same
 * format the app imports and exports, parsed by the same code — so the file
 * that ships is exercised by every OPML test rather than by a separate
 * "defaults" path that nothing else touches.
 *
 * Seeding happens **once**, guarded by [Prefs.seeded] rather than by "the feed
 * table is empty". A reader who deletes every feed on purpose should not be
 * handed a hundred back on the next launch.
 */
object Seed {

    const val ASSET = "default_feeds.opml"

    /**
     * @param repo the process's single [Repo]. Passed in rather than
     *   constructed here: a second [Db] over the same file means a second
     *   connection pool, and the first thing this does is write a hundred rows.
     */
    fun ifNeeded(context: Context, repo: Repo) {
        if (Prefs.seeded(context)) return
        val app = context.applicationContext
        if (!repo.isEmpty()) {
            // Feeds already exist — an import ran before the seed did. Record
            // the seed as done so it never lands on top of the reader's own set.
            Prefs.setSeeded(app)
            return
        }
        val xml = Safely.call({ app.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }, "")
        if (xml.isBlank()) return
        val added = install(repo, Opml.parse(xml), makeStreams = false)
        if (added > 0) Prefs.setSeeded(app)
    }

    /**
     * Write groups of feeds.
     *
     * Shared between the first run and OPML import, and the difference between
     * them is [makeStreams]:
     *
     *  - **the first run passes false.** A group becomes a *category* the feeds
     *    are tagged with, and nothing else. No streams are created.
     *  - **an import passes true.** Someone arriving from another reader has
     *    folders they built deliberately, and throwing that structure away
     *    would be rude. Each folder becomes both a category and a stream that
     *    includes that category — so the stream keeps working when they add
     *    another feed to it later.
     *
     * A feed that already exists is not added twice; it is tagged if it had no
     * category, and joins the stream either way.
     *
     * @return how many feeds this call put in the database.
     */
    fun install(repo: Repo, groups: List<Opml.Group>, makeStreams: Boolean): Int {
        var count = 0
        val builtins = Categories.ORDER.toSet()
        val existingStreams = repo.streams().associateBy { it.name.lowercase() }

        for (group in groups) {
            val category = group.name?.trim()?.takeIf { it.isNotEmpty() }
            if (category != null) repo.createCategory(category, builtin = category in builtins)

            for (entry in group.feeds) {
                // addFeed returns the existing row's id for a URL already
                // subscribed, so "how many were added" has to be asked before
                // the call — otherwise re-importing the same OPML reports a
                // hundred new feeds and none of them are.
                val fresh = repo.feedByUrl(entry.url) == null
                val id = repo.addFeed(
                    url = entry.url,
                    title = entry.title,
                    siteLink = entry.siteUrl,
                    category = category,
                )
                if (fresh && id > 0) count++
            }

            if (!makeStreams || category == null || group.feeds.isEmpty()) continue

            // A category rule rather than a list of feed ids: the stream then
            // keeps up with the category instead of freezing at import time.
            val existing = existingStreams[category.lowercase()]
            if (existing != null) {
                repo.setStreamCategories(existing.id, repo.streamCategories(existing.id) + category)
            } else {
                repo.createStream(name = category, categories = listOf(category))
            }
        }
        return count
    }
}

# Workflows

`.github/workflows/` is a protected path for the tool that writes this repo to
the Windows box, and GitHub rejects a push that creates one without the
`workflow` token scope. So the workflow is kept here and moved into place by
hand:

```sh
mkdir -p .github/workflows
git mv workflows/build.yml .github/workflows/build.yml
```

`build.yml` runs `tools/verify_all.sh` (no Android SDK needed), then
`testDebugUnitTest` and `assembleDebug`, and uploads the APK and the test
report.

It deliberately does **not** run `tools/check_feeds.py`: that one reaches a
hundred publishers' servers, and a CI job that does so on every push is both
rude and a good way to get an IP blocked. Run it by hand before a release.

# Releasing CoreGovernor from GitHub Codespaces

The repository is configured so that release preparation starts in GitHub Codespaces and the tagged push triggers GitHub Actions. Codespaces does not hold the signing key and does not build the APK for publication; GitHub Actions performs the clean checkout, tests, signing, APK build, checksum generation, and GitHub Release upload.

## One-time GitHub setup

1. Open the repository in GitHub Codespaces.
2. In **Repository Settings → Secrets and variables → Actions**, add these **repository secrets**:
   - `ANDROID_KEYSTORE_BASE64`: base64 of the exact keystore used for updates to the existing app.
   - `ANDROID_KEYSTORE_PASSWORD`: keystore password.
   - `ANDROID_KEY_ALIAS`: the signing key alias inside that keystore.
   - `ANDROID_KEY_PASSWORD`: password of that signing key.
3. Give the repository Actions permission to create releases: **Settings → Actions → General → Workflow permissions → Read and write permissions**. If the organization overrides this, an administrator must allow it.
4. Protect the release tag pattern if desired. The workflow accepts tags in the form `vMAJOR.MINOR.PATCH`, such as `v3.6.0`.

Never commit the keystore, `.env`, passwords, or a base64 secret to the repository. The existing `.gitignore` excludes these files.

## Release from Codespaces

From the repository root, check the version and working tree:

```bash
sed -n '30,40p' app/build.gradle.kts
git status
```

For a prepared release, run:

```bash
./scripts/release-from-codespaces.sh 3.6.0
```

The script refuses to run if the working tree is dirty, if the app version does not match, or if the tag already exists. It then commits the release, pushes the current branch, creates `v3.6.0`, and pushes that tag.

Pushing `v3.6.0` starts **Actions → Release Signed APK**. The workflow:

1. Verifies that the tag and `versionName` match.
2. Uses Java 17 and the committed Gradle 9.3.1 wrapper.
3. Installs Android API 36/build tools 36.0.0.
4. Runs `:app:testDebugUnitTest`.
5. Decodes the keystore only in the temporary GitHub runner workspace.
6. Builds `app-release.apk` with the configured alias.
7. Publishes `CoreGovernor-v3.6.0-release.apk` and its `.sha256` file to the GitHub Release.
8. Deletes the temporary keystore even if the build fails.

## Checking the result

In Codespaces, monitor the run with:

```bash
gh run list --workflow release.yml --limit 5
gh run watch
```

Then open the repository's **Releases** page and verify the APK and checksum are attached to `v3.6.0`.

## Future releases

Update `versionName` and increment `versionCode` in `app/build.gradle.kts`, move the new notes from `Unreleased` to a dated changelog heading, commit the source changes, and run the same script with the new version. Do not reuse a release tag or a version code already published to users.

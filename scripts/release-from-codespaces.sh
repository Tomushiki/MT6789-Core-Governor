#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
BRANCH="${RELEASE_BRANCH:-$(git branch --show-current)}"

if [[ -z "$VERSION" || ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: $0 <version>, for example: $0 3.6.0" >&2
  exit 2
fi
if [[ -z "$BRANCH" || "$BRANCH" == "HEAD" ]]; then
  echo "Run this from a checked-out branch, not detached HEAD." >&2
  exit 2
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Review or commit changes before releasing." >&2
  git status --short >&2
  exit 1
fi

PROJECT_VERSION="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' app/build.gradle.kts)"
if [[ "$PROJECT_VERSION" != "$VERSION" ]]; then
  echo "app/build.gradle.kts contains versionName $PROJECT_VERSION, expected $VERSION." >&2
  exit 1
fi

TAG="v$VERSION"
if git rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null; then
  echo "Tag $TAG already exists locally." >&2
  exit 1
fi
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  echo "Tag $TAG already exists on origin." >&2
  exit 1
fi

git diff --check
git add -A
git commit -m "Release CoreGovernor $VERSION"
git push origin "$BRANCH"
git tag -a "$TAG" -m "CoreGovernor $VERSION"
git push origin "$TAG"

echo
echo "Release tag $TAG pushed. GitHub Actions will test, sign, and publish the APK release."

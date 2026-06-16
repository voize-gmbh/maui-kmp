#!/bin/bash

VERSION=${1}
GIT_TAG=v${VERSION}
BASEDIR=$(dirname $(readlink -f "$0"))
(
    cd "$BASEDIR/.."
    # perl -i is portable across GNU/BSD (macOS); `sed -i` differs between them.
    perl -i -pe "s/version=.*/version=${VERSION}/" kotlin/gradle.properties
    # Insert the release header right after the "## unreleased" line.
    perl -i -pe "s/^## unreleased\$/## unreleased\n## ${GIT_TAG}/" CHANGELOG.md
    git commit -m "version ${VERSION}" kotlin/gradle.properties CHANGELOG.md
    git tag -a $GIT_TAG -m "version ${VERSION}"
)

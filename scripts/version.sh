#!/bin/bash

VERSION=${1}
GIT_TAG=v${VERSION}
BASEDIR=$(dirname $(readlink -f "$0"))
(
    cd "$BASEDIR/.."
    sed -i "s/version=.*/version=${VERSION}/g" kotlin/gradle.properties
    sed -i "/\#\# unreleased/a \#\# ${GIT_TAG}" CHANGELOG.md
    git commit -m "version ${VERSION}" kotlin/gradle.properties CHANGELOG.md
    git tag -a $GIT_TAG -m "version ${VERSION}"
)

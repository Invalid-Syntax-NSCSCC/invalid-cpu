#!/bin/sh

set -e

git switch main
git pull
git branch -D "$1"
git switch -c "$1"
git branch -u origin/"$1" "$1"
git push -f
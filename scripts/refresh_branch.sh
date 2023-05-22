#!/bin/sh

set -e

echo
while true; do
  echo "Are you wish to refresh branch \`$1'? This operation will DISCARD all your unmerged work on branch \`$1' and CANNOT be reverted!"
    read -r yn
    case $yn in
        [Yy]* )
          break
          ;;
        * )
          echo "Operation aborted."
          exit
          ;;
    esac
done

git switch main
git pull
git branch -D "$1"
git switch -c "$1"
git branch -u origin/"$1" "$1"
git push -f

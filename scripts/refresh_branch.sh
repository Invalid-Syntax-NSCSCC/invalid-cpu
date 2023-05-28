#!/bin/sh

set -e

USAGE=$(cat <<EOF
refresh_branch.sh

DESCRIPTION:
An automation tool for removing a specific branch and create a new one from main branch.
DO NOT use this tool if the given branch has not been merged to main!

USAGE:
    ./refresh_branch.sh <branch>

FLAGS:
    -h, --help       Prints help information

ARGS:
    <branch>     The branch to operate
EOF
)

case $1 in
  -h | --help | '' )
    echo "$USAGE"
    exit
    ;;
esac

echo
while true; do
  echo "Do you wish to refresh branch \`$1'? This operation will DISCARD all your unmerged work on branch \`$1' and CANNOT be reverted! (yes/no, default: no)"
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

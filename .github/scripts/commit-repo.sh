#!/bin/bash
set -e

rsync -a --delete --exclude .git --exclude .gitignore --exclude .gitattributes --exclude repo.json ../master/repo/ .
git lfs install
git config --global user.email "${BOT_EMAIL:-github-actions[bot]@users.noreply.github.com}"
git config --global user.name "${BOT_NAME:-github-actions[bot]}"
git checkout -B repo
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    MSG="Update extensions repo"
    if [[ $(git log -1 --pretty=format:%s 2>/dev/null) =~ "$MSG" ]]; then
      git commit --amend --no-edit
      git push --force origin HEAD:refs/heads/repo
    else
      git commit -m "$MSG"
      git push origin HEAD:refs/heads/repo
    fi
else
    echo "No changes to commit"
fi

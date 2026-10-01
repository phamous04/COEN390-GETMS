# Branches & Pull Requests Guide

How we work on this project: **nobody pushes directly to `main`**. All changes go through a **branch** and a **Pull Request (PR)** that needs **at least one reviewer's approval** before it can be merged.

---

## The Big Picture

```
main ───●───────────────────────●───────●──  (protected: no direct pushes)
         \                     /
          ●───●───●───●───────●             your branch → PR → review → merge
        (your commits)
```

1. Create a branch from `main`
2. Commit your work on that branch
3. Push the branch to GitHub
4. Open a Pull Request
5. A teammate reviews and approves
6. Merge into `main`, delete the branch

---

## Part 1: Branches

A **branch** is your own separate line of work. Changes on your branch don't affect `main` until the PR is merged.

### Start a new branch

Always start from an up-to-date `main`:

```bash
git checkout main
git pull
git checkout -b feature/short-description
```

(`git switch -c feature/short-description` does the same thing in newer Git versions.)

### Branch naming

Use a prefix + short, lowercase description with dashes:

| Prefix | Use for | Example |
|---|---|---|
| `feature/` | New functionality | `feature/login-page` |
| `fix/` | Bug fixes | `fix/crash-on-empty-name` |
| `docs/` | Documentation | `docs/update-readme` |
| `chore/` | Cleanup, config, dependencies | `chore/update-packages` |

**One task per branch.** Small branches are easier to review and merge.

### Useful branch commands

```bash
git branch                    # list local branches (* = current one)
git branch -a                 # list local + remote branches
git checkout <name>           # switch to an existing branch
git checkout -b <name>        # create and switch to a new branch
git branch -d <name>          # delete a branch that's already merged
git status                    # shows which branch you're on
```

### Save your work on the branch

```bash
git add .
git commit -m "Add validation to login form"
git push -u origin feature/short-description    # first push of a new branch
git push                                         # every push after that
```

### Switching branches with unsaved changes

If Git refuses to switch because of uncommitted edits, either commit them or set them aside:

```bash
git stash          # temporarily put changes away
git checkout other-branch
# ...later, back on your original branch:
git stash pop      # bring the changes back
```

---

## Part 2: Pull Requests (PRs)

A PR asks the team to merge your branch into `main`. It's where code gets reviewed, discussed, and approved.

### Step 1: Open the PR

1. Push your branch (see above).
2. Go to the repo on GitHub. You'll usually see a yellow banner: **Compare & pull request**. Click it.
   - Otherwise: **Pull requests** tab → **New pull request** → set *base:* `main` and *compare:* your branch.
3. Fill in:
   - **Title**: short summary, e.g. `Add validation to login form`
   - **Description**: what you changed, why, and how to test it
4. On the right sidebar, add at least one **Reviewer**.
5. Click **Create pull request**.

> **Draft PRs:** if your work isn't ready yet, choose **Create draft pull request**. Click **Ready for review** when you're done.

### What makes a good PR

- Small and focused (one task)
- Clear title and description
- You've looked over your own changes first (**Files changed** tab)
- Tests/app still work on your machine

### Step 2: Review process

Because `main` is protected, your PR **cannot be merged until**:

- At least one teammate **approves** it
- (Any other checks the team enabled pass, e.g. automated tests)

The **Merge** button stays disabled until these are met.

### Step 3: Respond to feedback

If the reviewer requests changes:

1. Stay on your **same branch** locally.
2. Make the fixes, then:
   ```bash
   git add .
   git commit -m "Address review feedback"
   git push
   ```
3. The PR updates automatically. **Don't open a new one.**
4. Reply to comments on GitHub and click **Resolve conversation** when addressed.
5. Re-request review using the circular arrow icon next to the reviewer's name.

### Step 4: Merge

Once approved, click **Merge pull request** (or let the designated person do it).

GitHub may offer merge options:

| Option | What it does |
|---|---|
| **Create a merge commit** | Keeps all your commits plus a merge commit |
| **Squash and merge** | Combines all your commits into one clean commit on `main` |
| **Rebase and merge** | Replays your commits on top of `main` |

Use whichever the team lead tells you to. If unsure, ask.

### Step 5: Clean up

1. On GitHub, click **Delete branch** after merging.
2. On your computer:
   ```bash
   git checkout main
   git pull
   git branch -d feature/short-description
   ```

---

## Part 3: Reviewing Someone Else's PR

1. Open the PR from the **Pull requests** tab.
2. Click **Files changed** to see the diff.
3. Hover over a line and click the blue **+** to leave a comment on that line.
4. When done, click **Review changes** and pick one:
   - **Comment**: general feedback, no decision
   - **Approve**: good to merge
   - **Request changes**: must be fixed before merging
5. Click **Submit review**.

**Reviewing tips:** be kind and specific, ask questions instead of making demands, and review promptly so teammates aren't blocked.

---

## Part 4: When Things Go Wrong

### "Push rejected" when pushing to `main`

You'll see something like:

```
remote: error: GH006: Protected branch update failed for refs/heads/main.
```

This is expected. Direct pushes to `main` are blocked. If you committed on `main` by mistake and **haven't pushed successfully**, move your work to a branch:

```bash
git branch feature/my-task          # create a branch at your current commit
git reset --hard origin/main        # reset local main to match GitHub
git checkout feature/my-task        # continue on your branch
```

> `reset --hard` discards uncommitted changes on `main`, so make sure your work is committed first. Ask a teammate if unsure.

### Your branch is behind `main`

Someone else's PR was merged and your branch is out of date. Pull the latest `main` into your branch:

```bash
git checkout main
git pull
git checkout feature/my-task
git merge main
git push
```

GitHub also shows an **Update branch** button on the PR page that does this for you.

### Merge conflicts

A conflict means two people changed the same lines. Git marks the file like this:

```
<<<<<<< HEAD
Your version of the line
=======
Their version of the line
>>>>>>> main
```

To resolve:

1. Open the file, choose what the final text should be, and delete the `<<<<<<<`, `=======`, `>>>>>>>` lines. (VS Code has *Accept Current / Incoming / Both* buttons.)
2. Save, then:
   ```bash
   git add the-file.txt
   git commit -m "Resolve merge conflict"
   git push
   ```

To cancel a merge and go back to how things were: `git merge --abort`.

### Pushed to the wrong branch / need to rename a branch

```bash
git branch -m new-name              # rename the current branch locally
```
Ask a teammate for help if the branch is already pushed or has an open PR.

---

## Cheat Sheet

```bash
# Start a task
git checkout main
git pull
git checkout -b feature/my-task

# Save and share work
git add .
git commit -m "Describe the change"
git push -u origin feature/my-task     # first push
git push                               # later pushes

# Then on GitHub: open PR → add reviewer → get approval → merge → delete branch

# After merging
git checkout main
git pull
git branch -d feature/my-task
```

## Rules of Thumb

1. **Never commit or push directly to `main`.**
2. **Pull `main` before creating a new branch.**
3. **One task per branch, one branch per PR.**
4. **Keep PRs small** so they're quick to review.
5. **Delete branches after merging.**
6. **Ask for help early.** Branch and PR problems are common and usually quick to fix.

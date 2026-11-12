# Check in and move Investigation Hub

The workspace has a local Git repository on branch `feature/platform-foundation`, with no commits or remote yet. Create a private repository with your Git provider, then run these commands from the workspace root. Replace `<REMOTE_URL>` with the repository URL; do not paste a token into that URL.

```sh
git add .
git status --short
git diff --cached --stat
git commit -m "Add Investigation Hub platform foundation"
git remote add origin <REMOTE_URL>
git push -u origin feature/platform-foundation
```

Before committing, inspect `git status --short`: it should show source, migrations, contracts, and documentation. It should **not** show `.cache/`, `target/`, `.env`, keys, database dumps, or logs; `.gitignore` excludes these. Do not commit the local PostgreSQL data. Fresh databases are initialized by the schema script, Flyway migrations, and seeded synthetic policies.

On another machine, clone the repository and read `AGENTS.md`, `CLAUDE.md`, and `docs/PROJECT_STATE.md` before asking an assistant to change code. In Claude Code, `CLAUDE.md` is the entry point; in other coding agents, direct them to `AGENTS.md`. These files are a handoff, not a substitute for reading source or running verification.

```sh
git clone <REMOTE_URL> "Investigation HUB"
cd "Investigation HUB/Backend Projects/platform"
export IH_DB_PASSWORD='<your-local-postgres-password>'
bash scripts/run-local.sh --smoke
bash scripts/run-local.sh
```

The second command keeps services running. The script expects Java 25, Maven, PostgreSQL, `psql`, `curl`, `rg`, `lsof`, and Python 3.9+. Set `JAVA_HOME` if Java 25 is not the default. For a non-default PostgreSQL connection, set `IH_DB_HOST`, `IH_DB_PORT`, `IH_DB_NAME`, and `IH_DB_USER` before running. The user must be able to create the five schemas. The local script uses Bash and is suited to macOS/Linux or Windows WSL. Run `python3 case_routing_scenarios.py` in another terminal to create new demonstration cases and URLs.

The target UI is Next.js, but no Next.js application has been created yet. The two HTML monitors are development views. If another assistant claims the full agentic platform, external sections, policy upload, or authentication already works, compare the claim with `docs/PROJECT_STATE.md` and the code.

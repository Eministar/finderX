# Contributing

## Development setup

1. Install JDK 21+ and Maven.
2. Clone repo.
3. Run `mvn javafx:run`.

## Guidelines

- Keep UI responsive: never block JavaFX thread.
- Keep search async and cancellable.
- Prefer clean, minimal black/white design language.
- Keep code readable and focused on performance.

## Pull requests

1. Create feature branch.
2. Add/adjust tests if behavior changes.
3. Ensure `mvn -DskipTests compile` passes.
4. Open PR with short technical summary.

## Commit style

Use concise imperative commits, e.g.:

- `feat(search): speed up prefix matching`
- `style(ui): simplify table visuals`
- `build(release): add jpackage script`

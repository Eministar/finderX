# FinderX Theme Authoring

This repository supports custom CSS themes loaded from:

`%USERPROFILE%/.finderx/themes` (Windows)  
`~/.finderx/themes` (Unix-like)

## How Theme Discovery Works

- FinderX scans the themes folder for `*.css`.
- Official bundled themes are synchronized into that folder automatically.
- Official bundled files are refreshed from repository resources when FinderX starts/scans themes.
- The active theme is selected in Settings -> Theme support.
- Exactly one theme is active at a time.

## Metadata Header (Recommended)

Add this comment block at the top of your CSS file:

```css
/*
fx-theme-name: My Theme
fx-theme-author: Your Name
fx-theme-description: Short explanation of your visual style.
*/
```

These fields are shown in the theme cards in-app.

## Important Rules

- `official` status is not controlled by CSS.
- Even if you write a fake official marker, FinderX ignores it.
- Official themes are determined internally by bundled filenames only.

## Create A New Theme Quickly

In Settings -> Theme support:

1. Click `Create new theme`.
2. FinderX creates a new CSS file in your themes folder.
3. Your default editor opens that file immediately.
4. Save changes, then return to FinderX and refresh/focus the theme dialog.

## Best Practices

- Start by changing a few high-impact selectors:
  - `.window-shell`
  - `.window-bar`
  - `.top-container`, `.center-card`, `.bottom-bar`
  - `.search-field`
  - `.file-table .column-header-background`
  - `.file-table .column-header .label`
  - `.file-table .table-cell`
  - `.file-table .table-row-cell:filled:selected`
  - `.settings-window`, `.settings-header`, `.settings-btn-primary`
- Keep text contrast high enough for readability.
- Test both selected and hover states for table rows/buttons.

## Minimal Example

```css
/*
fx-theme-name: Aurora
fx-theme-author: You
fx-theme-description: Blue-green neon accents with dark base.
*/

.window-shell {
    -fx-background-color: linear-gradient(to bottom, #0d1220, #101729);
    -fx-border-color: #355181;
}

.settings-btn-primary {
    -fx-background-color: #c8f1ff;
    -fx-border-color: #c8f1ff;
    -fx-text-fill: #0b1e2f;
}
```

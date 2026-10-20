# DESIGN.md — Integration Mapper console

Decided in the design review (`docs/slice-plan.md`, D-1 … D-20). The console is an app for finishing a task (operate mode): calm surfaces, dense but readable, one accent, colors that always mean the same thing. Implemented in `engine/console/src/theme.ts`.

## Type

| Role | Font | Notes |
|---|---|---|
| UI text | IBM Plex Sans (400, 500, 600) | Body 16px minimum; self-hosted via `@fontsource`, no CDN (closed networks) |
| Field paths, JSON, curl, ids | IBM Plex Mono (400) | Also used for link partners such as `← firstName` (D-20) |

Both fonts are SIL OFL.

## Color meanings (fixed, never decorative)

| Meaning | Mantine color | Used for |
|---|---|---|
| Accent | `teal` (shade 8) | Primary actions, selected source while linking (D-11), live status, normal links (D-20) |
| Warning | `yellow` (amber) | Something to look at, does not block: required field unmapped, guessed type (D-6, D-13) |
| Broken / not allowed | `red` | Broken or incompatible link, invalid input, publish blocked (D-6, D-7, R8) |
| Passed | `green` | Only "test passed" (D-2) |
| Neutral | `gray` | Drafts, secondary text |

A color is never the only signal: it always comes with an icon and words (D-16, D-17).

## Space and shape

- 4px scale (Mantine `xs`=4, `sm`=8, `md`=16, `lg`=24, `xl`=32).
- Small radius (4px) everywhere; no large rounded cards.
- Light theme only in the slice.

## Layout

- Desktop first, from 1280px. Below 1024px the mapping step shows "Use a wider screen to map fields"; other pages work at any width (D-17).
- Header: product name, then the two areas: **Integrations** and **Payload definitions** (D-1, D-8).
- An integration shows the 5-step stepper: 1 Payload, 2 Import, 3 Operation, 4 Map, 5 Publish. Connection settings live on the integration's Settings page, not in the stepper (D-3).

## Words

- Type names everywhere (D-4): text, number, whole number, yes/no, date, date-time, object, list.
- Errors say what is wrong and where, in plain words; the engine's messages are shown as they are.
- Empty states say what the page is for and offer the one next action (D-5).

## Accessibility

Visible focus ring in the accent color, WCAG AA contrast, all actions reachable by keyboard, warnings announced via a live region (D-17).

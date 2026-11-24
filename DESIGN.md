# DESIGN.md — Patchbay console

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
| Accent | `teal` (shade 9, `#087f5b`) | Primary actions, selected source while linking (D-11), live status, normal links (D-20) |
| Warning | `yellow` (amber) | Something to look at, does not block: required field unmapped, guessed type (D-6, D-13) |
| Broken / not allowed | `red` | Broken or incompatible link, invalid input, publish blocked (D-6, D-7, R8); also the confirming button of a destructive dialog, but never a row action |
| Passed | `green` | Only "test passed" (D-2) |
| Neutral | `gray` | Drafts, secondary text (`#62696f`, see Accessibility), done states that are not a test result ("All required fields mapped") |

A color is never the only signal: it always comes with an icon and words (D-16, D-17).

## Space and shape

- 4px scale (Mantine `xs`=4, `sm`=8, `md`=16, `lg`=24, `xl`=32).
- Small radius (4px) everywhere; no large rounded cards.
- Light theme only in the slice.

## Layout

- Desktop first, from 1280px. Below 1024px the mapping step shows "Use a wider screen to map fields"; other pages work at any width (D-17).
- Header: the Patchbay logo and name (links to Integrations), then **Integrations**, **Payload definitions** and **Configuration** (D-21, D-25). The logo is a teal (shade 9) tile with jacks on both sides and two cables: filled jacks are connected, hollow ones free, as on the board. Files: `engine/console/src/components/Logo.tsx`, `engine/console/public/favicon.svg`, `docs/images/patchbay-logo.svg`.
- An integration is one page, no wizard (user decision 2026-10-22, replaces the D-2 stepper). Header actions in this order: Settings, Test, Calls, **Publish** (the primary action, last). Under it the setup bar, in the order the work goes: External system, Operation, Input, Output (an Input or Output left empty is created from the operation, already linked); the Operation select is twice as wide and shows method and path, with the operation's summary on a line under it. Until an operation, Input and Output are chosen, Test and Publish say what is missing. A tree with no fields says why (no fields in the definition, an operation that sends or answers nothing, or a contract that doesn't describe that part), and a tab whose two sides have fields but no link warns in amber, as the publish dialog does. Then the mapping board. Connection settings live on the integration's Settings page (D-3).
- The mapping board fills the viewport height once scrolled to (the trees are never shorter than 360px). Lines are teal when valid, red when they can't be published, dashed when an end no longer exists. A field that no longer exists gets its own row under "No longer exists" (red, struck through), so its broken link is drawn like any other. The × that removes a link shows only while its line is pointed at or the × has focus. A **Problems** filter (shown only when there are problems) narrows both trees to the ends of links that can't be published; the status bar's problem counts switch to it. The text list of links under the board is folded and always lists the problem links; it is also the keyboard and screen-reader way to read and remove links.
- When something blocks publishing, Publish turns grey with a red count (the publish dialog's count). The dialog can remove all links to fields that no longer exist in one step.

## Lists and actions

- A list row opens its item; there is no separate Open or Select button.
- Destructive actions are not a column of red buttons: they sit in a "⋯" menu per row, or as a gray icon, and are confirmed in a dialog whose confirming button is red.
- A list longer than a screen has a search box; Integrations also has a status switch (All, Live, Draft, Broken) with counts. Search matches every typed word.
- An id or slug is shown under a name only when it differs from the name.
- Dates in tables are short ("today, 18:24", "yesterday, 09:05", "3 Nov 2026") with the full timestamp on hover.
- Hints belong in labels and column headers, not in placeholders: a placeholder never says "optional" or shows "—", and an example placeholder says it is one ("Header name, e.g. X-Correlation-Id").
- An action that can't be used says why. It stays focusable (`data-disabled`, `aria-disabled`, not `disabled`) so the reason shows on keyboard focus as well as on hover; where nothing can be done, words replace the control (a constant's Required cell says "constant").
- A page whose list is empty shows its one task in place (Configuration shows the import form); once there is something in the list, that task moves behind a button and a dialog.

## Words

- Type names everywhere (D-4): text, number, whole number, yes/no, date, date-time, object, list.
- Errors say what is wrong and where, in plain words; the engine's messages are shown as they are.
- Empty states say what the page is for and offer the one next action (D-5).

## Accessibility

Visible focus ring in the accent color, WCAG AA contrast, all actions reachable by keyboard, warnings announced via a live region (D-17).

- Secondary text and placeholders use `#62696f` (set in `theme.ts` through the provider's `cssVariablesResolver`): at least 5:1 on white and on the light gray, teal, yellow and red backgrounds the console uses. Mantine's defaults, `#868e96` and `#adb5bd`, reach only 3.3:1 and 2.1:1.
- Popovers that hold links move focus into themselves and close on Escape (`trapFocus`, `returnFocus`).
- The board's trees follow the WAI-ARIA tree pattern: each tree is one Tab stop (its current row); arrow keys, Home, End, Page Up and Page Down move, scrolling a virtualized row into view before focusing it. Enter or Space on a source moves focus to the target tree; Enter on a target links and moves focus back to the source; Esc cancels and goes back too. The hint line under the tabs is a live region: it says what was linked, or why a link can't be made (in red), so the reason no longer lives only in a hover tooltip. A row that can't be used fades its content, never its focus ring.
- The accent is `teal.9` (5.0:1 on white) so links and white text on primary buttons meet AA; `teal.8` reached 3.95:1. The light teal behind teal text (active menu item, Live badges, selected row) is `teal.0`, giving 4.7:1.
- Graphics that carry meaning reach 3:1 (WCAG 1.4.11): the board's free dots are `gray.6`, linked dots `teal.7` / `red.7`, valid lines `teal.8`, problem lines `red.6`.
- Authentication stays per integration (user decision 2026-11-24): one system can mix open and protected operations, and integrations can use different credentials. The Settings page says so and points to connection headers for a key every call needs.
- Status text and the icons beside it use `statusText` in `theme.ts`: warning `#9c5d00`, broken `red.9`, passed `#247534`, at least 4.9:1 on white and on the light backgrounds (Mantine's `yellow.8`, `green.8` and `red.7` reached 2.5, 3.5 and 3.8:1). Mantine's light variants (alert titles, light buttons) use the same amber and green.

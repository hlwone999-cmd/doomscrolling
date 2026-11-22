# Brand: Tidemark

"BrainPal Alternative" stays the **project folder name only**. It contains the original's trademark and is never shown in the app.

## Name

**Tidemark**: the line that shows how high the water reached. *See how far you scrolled. Then let it ebb.*

Screening checks (2026-11-21, web search only; this is **not** legal clearance):

| check | result |
| --- | --- |
| web: "Tidemark" app on Android | no app with that name found |
| web: Tidemark software | **risk:** Tidemark was an enterprise financial-planning product (Tidemark Systems, later Longview / insightsoftware). Different market, but possibly a registered mark in software classes 9/42 |
| USPTO, EUIPO/TMview, WIPO, Indonesia DJKI | to run |
| domain, Play Store exact name, social handles | to run |

**A trademark lawyer should check this before you spend money on the name.**

Rejected candidates: Swipewise and Tallo (already in use), Ebbwise (harder to say in Indonesian), Reelmark (an existing fishing app).

## Palette

The tokens keep their role names (`replica/design/tokens.json`).

- **Accent:** sea teal, light `#0B6670` and dark `#6FD3D9`. Replaces the placeholder blue.
- **Brand:**
  - `deep` `#0B4F59`: the icon tile.
  - `foam` `#E8FBF8`: the mark.
  - `mark` `#FF9A76`: the coral tidemark line.

Contrast: 81 pairs, 0 AA failures. The original's colours were never observed, so `brand.json` lists none.

## Logo and icon

The idea: a level gauge, a wave, and a coral line marking the highest level. It shares no shape, colour pair or letterform with the original's brain imagery.

- **Android** (`res/`):
  - `mipmap-anydpi-v26/ic_launcher(_round).xml`: an adaptive icon with background, foreground and monochrome layers (themed icons on Android 13+).
  - `drawable/ic_launcher_*.xml`: the three layers. The foreground stays inside the 66 dp safe zone (checked).
  - `ic_stat_counter.xml`: the status-bar icon, same motif in one colour.
- **Files** (`replica/brand/`): `icon.svg`, `icon-monochrome.svg`, `logo.svg` (wordmark set as text in Inter; outline it before printing), `play-icon-512.png`, `feature-graphic-1024x500.png`, `icon-preview.png` (circle, squircle, rounded and themed masks, on light and dark wallpapers), `icon-16.png`, `icon-48.png`.
- Re-render everything with `python replica/brand/render.py`.

## Type

**Inter** variable (SIL Open Font License 1.1), from github.com/google/fonts.

- In the app: `res/font/inter_variable.ttf`. Every text style uses it through `AppFonts.Inter`, and the overlays use it too. The widget keeps the system font.
- Licence: `assets/licenses/inter_OFL.txt`, shown in Settings > Open-source licences.
- Brand copy in `replica/brand/fonts/`.

## Voice

| we are | but not |
| --- | --- |
| calm | preachy |
| plain | clinical |
| kind | cute |

| do | don't |
| --- | --- |
| say what happened ("Counting stopped") | blame the user |
| offer a choice ("Keep going, or take a break: your call") | lecture or shame |
| use numbers as facts | judge them as "bad" |
| say what the app reads, plainly | hide it in legal wording |
| use short sentences | use exclamation marks |

The most-seen strings were rewritten in this voice, in English and Indonesian: the disclosure title and intro, the permissions title, Today's empty state, the block screen, the nudge, the goals empty state, the "counting stopped" notice and the widget name.

## Identifiers

| identifier | value | note |
| --- | --- | --- |
| app name | Tidemark | |
| `applicationId` | `app.tidemark` | **Replace with a reverse domain you own before publishing.** It can't change after the first release. |
| Kotlin package | `app.tidemark` | |
| database | `tidemark.db` | |
| export header | `# Tidemark export v1` | |

## Sweep

`python .claude/skills/replica-brand/sweep.py android --config replica/brand.json` reports **Clean**: none of the original's names or domain, and no leftover placeholder name.

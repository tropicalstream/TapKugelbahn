# TapKugelbahn 🎱🥽

A kinetic rolling-ball sculpture — a Kugelbahn — for the **RayNeo X3 Pro**
smart glasses, drawn entirely in glowing vector lines. One closed loop of
track winds around you as a panoramic cylinder: gravity funnels, a
loop-the-loop, corkscrews, a ferris wheel, interlocking double spirals,
snake runs, a Newton's cradle, a rocker arm, a counterweighted tipping
bucket, a gauss cannon, pachinko pins, an Archimedes screw, a trommel,
xylophone stairs, spinning propellers — and a chain elevator that lifts
every ball back to the top, forever.

## The game

- **TAP** drops a ball into the intake from five ball-diameters up.
- **The rule:** two balls must run the course together — the second dropped
  before the first finishes — and they must never touch. Manage the spacing.
- **SWIPE** cycles the view: an auto-director that tracks the first ball and
  cuts between vantages on its own, then outside orbit, inside panorama,
  zoomed side view, and a follow camera that keeps the ball fist-sized.
- **DOUBLE-TAP** advances once the level is complete. Balls keep looping via
  the elevator either way — it's a sculpture first.
- Four machines, each introducing five new mechanisms and each longer than
  the last, from a one-minute course to the floor-to-ceiling
  **Das Grosse Werk**.

## Real sounds

The clacks, xylophone strikes, ratchet ticks, triangle dings and the
rolling bed are real public-domain / CC recordings from Wikimedia Commons,
trimmed and repitched live — see `SOUND_CREDITS.md`.

## Build

Zero dependencies — OpenGL ES 3.0 straight from the framework.

```
./gradlew assembleDebug
```

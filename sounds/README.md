# Camera sounds (91a)

The shutter, film wind and dial sounds in `app/src/main/res/raw/snd_*.ogg` are not recordings. They are made
by `camera.py`, a small physical model of a camera: parts that ring when struck and bounce, springs, cloth and
film friction, the body's own resonances, and a close microphone in a small room. They belong to this project
and are under its licence.

Rebuild them (Python 3 with numpy and scipy, plus ffmpeg with libvorbis):

    python3 sounds/bake.py app/src/main/res/raw

Each camera (rangefinder, slr, compact) gets six takes of the shutter's open half, its close half and the film
wind; the app leaves the real exposure time between open and close. `Sounds.kt` plays them.

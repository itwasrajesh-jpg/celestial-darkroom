"""Bake the app's sounds into res/raw (91a): per camera 6 takes each of the shutter's open half, close half
and the film wind, plus 6 dial ticks, as small mono Ogg Vorbis files. python3 bake.py RES_RAW_DIR"""
import sys, os, subprocess, tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from camera import *
OUT = sys.argv[1]; TAKES = 6
tmp = tempfile.mkdtemp()
def ogg(name, x):
    x = x.copy(); k = int(0.004 * SR); x[-k:] *= np.linspace(1, 0, k)
    w = f'{tmp}/{name}.wav'; save(w, x)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', w, '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '5',
                    f'{OUT}/{name}.ogg'], check=True)
def trim(x, floor_db=-60):
    """Cut the silent tail so SoundPool frees the stream early."""
    a = np.abs(x); idx = np.where(a > 10 ** (floor_db / 20))[0]
    return x[:idx[-1] + int(0.01 * SR)] if len(idx) else x
for name, cam in [('rangefinder', Rangefinder()), ('slr', SLR()), ('compact', Compact())]:
    for t in range(1, TAKES + 1):
        o, c = halves(cam)
        ogg(f'snd_{name}_open_{t}', trim(o)); ogg(f'snd_{name}_close_{t}', trim(c))
        ogg(f'snd_{name}_wind_{t}', trim(cam.wind()))
for t in range(1, TAKES + 1):
    tick = finish(mic(Body([(900, 200, 1)], 0.3)(hit(Part(3000, 12000, 25, 50), 1, 0.05, 2, 0.6, 0.05)), wet=0.05), -12)
    ogg(f'snd_dial_{t}', trim(tick))

"""Celestial Darkroom sounds v2 (28 Oct 2026): a small physical model of a film camera.
Nothing recorded, nothing to license. Each part is a struck object (dense inharmonic modes) hit by a
short pulse that BOUNCES (2-4 smaller hits, closer and closer, like a dropped coin). Springs ring, film
drags, and everything is passed through the camera BODY (its own resonances), then a close mic in a room.
Every call draws new random details, so no two shots are identical.
python3 camera.py OUTDIR"""
import sys, numpy as np
from scipy.signal import butter, sosfilt, lfilter
from scipy.io import wavfile

SR = 48000
rng = np.random.default_rng(11)

def lp(x, f, o=2): return sosfilt(butter(o, f, 'low', fs=SR, output='sos'), x)
def hp(x, f, o=2): return sosfilt(butter(o, f, 'high', fs=SR, output='sos'), x)
def bp(x, lo, hi, o=2): return sosfilt(butter(o, [lo, hi], 'band', fs=SR, output='sos'), x)
def z(sec): return np.zeros(int(sec * SR))

def resonate(x, freqs, bws, gains):
    """Bank of 2-pole resonators driven by x (a struck object rings at its modes)."""
    y = np.zeros_like(x)
    for f, bw, g in zip(freqs, bws, gains):
        if f >= SR / 2 * 0.95: continue
        r = np.exp(-np.pi * bw / SR); w = 2 * np.pi * f / SR
        y += g * (1 - r * r) * lfilter([1.0], [1, -2 * r * np.cos(w), r * r], x)
    return y

class Part:
    """A metal or plastic part: dense modes between lo..hi Hz; damp = how fast it stops ringing."""
    def __init__(self, lo, hi, n, damp, tilt=-0.4):
        self.f = np.exp(rng.uniform(np.log(lo), np.log(hi), n))
        self.bw = self.f / rng.uniform(damp * 0.6, damp * 1.4, n)       # Q around `damp`
        self.g = (self.f / lo) ** tilt * rng.uniform(0.3, 1.0, n)
    def ring(self, x, detune=0.01):
        return resonate(x, self.f * rng.normal(1, detune, len(self.f)), self.bw, self.g)

def pulse(width_ms):
    """Contact force of one hit: a raised-cosine bump (hard = narrow) plus a little contact grit."""
    m = max(2, int(width_ms / 1000 * SR)); p = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(m) / m)
    return p + 0.15 * rng.standard_normal(m) * p

def hit(part, strength=1.0, hard=0.12, bounces=3, gap_ms=2.5, sec=0.12):
    """Strike with bounces: each bounce weaker and sooner than the last."""
    x = z(sec); t = 0.0; a = strength
    for k in range(bounces + 1):
        p = pulse(hard * (1 + 0.3 * k)) * a; i = int(t * SR)
        if i + len(p) < len(x): x[i:i + len(p)] += p
        t += gap_ms / 1000 * (0.55 ** k) * rng.uniform(0.8, 1.2); a *= rng.uniform(0.25, 0.45)
    return part.ring(x)

def spring(f0=900, sec=0.18, amt=0.05):
    """A coil spring released: a few close modes with a slow decay, slightly falling in pitch."""
    t = np.arange(int(sec * SR)) / SR; out = np.zeros_like(t)
    for k, (m, d) in enumerate([(1, .05), (1.53, .035), (2.71, .02), (4.1, .012)]):
        f = f0 * m * (1 - 0.03 * t / sec) * rng.uniform(.98, 1.02)
        out += np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / d) / (k + 1)
    return amt * out * (1 - np.exp(-t / 0.001))

def slide(sec, lo, hi, amt, grit=0.3):
    """Friction: cloth, film or metal sliding (band noise with crackle)."""
    n = int(sec * SR); x = rng.standard_normal(n)
    crack = (rng.random(n) < 0.004) * rng.standard_normal(n) * 6
    x = bp(x + grit * crack, lo, hi)
    env = np.sin(np.pi * np.linspace(0, 1, n)) ** 0.7
    return amt * x * env

class Body:
    """The camera shell + film chamber: a few low/mid resonances that colour every sound inside it."""
    def __init__(self, modes, mix=0.6):
        self.f = np.array([m[0] for m in modes]); self.bw = np.array([m[1] for m in modes]); self.g = np.array([m[2] for m in modes]); self.mix = mix
    def __call__(self, x): return (1 - self.mix) * x + self.mix * resonate(x, self.f, self.bw, self.g) * 3

def mic(x, room_ms=(3.1, 5.7, 9.4, 14.2, 21.0), wet=0.18, warmth=1.0, ref=None):
    """Close phone mic in a small room: early reflections, gentle saturation, faint room tone."""
    y = x.copy()
    for k, d in enumerate(room_ms):
        i = int(d / 1000 * SR); y[i:] += wet * (0.75 ** k) * lp(x, 5000 - 600 * k)[:len(x) - i] * (-1) ** k
    tail = rng.standard_normal(int(0.12 * SR)) * np.exp(-np.arange(int(0.12 * SR)) / (0.025 * SR))
    y = y + 0.05 * np.convolve(y, lp(tail, 4000) / 60)[:len(y)]
    y = np.tanh(warmth * 0.8 * y / (ref or (np.abs(y).max() + 1e-9))) / np.tanh(0.8 * warmth)
    y = hp(y, 60) + 0.0006 * lp(rng.standard_normal(len(y)), 3000)
    return y

def put(buf, snd, at):
    i = int(at * SR); j = min(len(buf), i + len(snd))
    if j > i: buf[i:j] += snd[:j - i]
    return buf

def finish(x, peak_db=-3, fade=0.015):
    x = x - x.mean(); k = int(fade * SR); x[-k:] *= np.linspace(1, 0, k)
    return x / (np.abs(x).max() + 1e-9) * 10 ** (peak_db / 20)

# ------------------------------------------------------------------ cameras
def halves(cam, wet=0.18):
    """For the app: the 'open' half and the 'close' half of one shot, on one shared level, so the app can
    leave the real exposure time between them. Returns (open, close) float arrays at full scale."""
    o, c = cam.open_raw(), cam.close_raw()
    o, c = cam.body(o), cam.body(c)
    ref = max(np.abs(o).max(), np.abs(c).max()) + 1e-9
    o, c = mic(o, wet=wet, ref=ref), mic(c, wet=wet, ref=ref)
    g = 10 ** (cam.peak_db / 20) / max(np.abs(o).max(), np.abs(c).max())
    return o * g, c * g

class SLR:
    """1970s all-metal SLR (think of the classic manual Nikons/Canons/Pentaxes): big mirror slap,
    horizontal cloth curtains, springs you can hear."""
    def __init__(self):
        self.body = Body([(180, 40, 1.0), (420, 70, .7), (1150, 160, .5), (2300, 300, .35)], 0.55)
        self.mirror = Part(500, 6000, 40, 25); self.stop = Part(1500, 11000, 40, 60, -0.2)
        self.curtain = Part(1800, 9000, 30, 35); self.foam = Part(150, 1200, 12, 6)
    peak_db = -3
    def open_raw(self):
        b = z(0.2)
        put(b, 0.25 * slide(0.004, 2000, 8000, 1), 0.000)
        put(b, hit(self.mirror, 1.0, 0.18, 3, 3.0) + 0.8 * hit(self.foam, 1.0, 0.6, 1, 4), 0.006)
        put(b, spring(780, 0.2, 0.04), 0.008)
        put(b, 0.35 * slide(0.009, 1500, 7000, 1), 0.018)
        put(b, hit(self.curtain, 0.7, 0.10, 2, 1.5), 0.027)
        return b
    def close_raw(self):
        b = z(0.3)
        put(b, 0.35 * slide(0.009, 1500, 7000, 1), 0.000)
        put(b, hit(self.stop, 0.9, 0.08, 3, 1.8), 0.009)
        put(b, hit(self.mirror, 0.85, 0.2, 4, 4.0) + 0.7 * hit(self.foam, 0.9, 0.7, 1, 4), 0.042)
        put(b, spring(640, 0.22, 0.035), 0.044)
        put(b, 0.3 * hit(self.stop, 0.5, 0.1, 1, 1), 0.057)
        return b
    def shutter(self, exp):
        b = z(0.32 + exp)
        put(b, 0.25 * slide(0.004, 2000, 8000, 1), 0.000)                            # release lever
        put(b, hit(self.mirror, 1.0, 0.18, 3, 3.0) + 0.8 * hit(self.foam, 1.0, 0.6, 1, 4), 0.006)  # mirror up into foam
        put(b, spring(780, 0.2, 0.04), 0.008)
        put(b, 0.35 * slide(0.009, 1500, 7000, 1), 0.018)                              # first curtain travels
        put(b, hit(self.curtain, 0.7, 0.10, 2, 1.5), 0.027)                            # ...and stops
        put(b, 0.35 * slide(0.009, 1500, 7000, 1), 0.018 + exp)                        # second curtain
        put(b, hit(self.stop, 0.9, 0.08, 3, 1.8), 0.027 + exp)                          # ...and stops (brake)
        put(b, hit(self.mirror, 0.85, 0.2, 4, 4.0) + 0.7 * hit(self.foam, 0.9, 0.7, 1, 4), 0.060 + exp)  # mirror back down
        put(b, spring(640, 0.22, 0.035), 0.062 + exp)
        put(b, 0.3 * hit(self.stop, 0.5, 0.1, 1, 1), 0.075 + exp)                      # aperture reopens
        return finish(mic(self.body(b)), -3)
    def wind(self):
        return lever_wind(self.body, Part(2500, 12000, 30, 45), 0.42, 26)

class Rangefinder:
    """Quiet rangefinder with a cloth focal-plane shutter (Leica-like): no mirror, a soft double 'tk'."""
    def __init__(self):
        self.body = Body([(260, 50, 1.0), (700, 90, .6), (1800, 220, .4)], 0.5)
        self.curtain = Part(1200, 6000, 30, 22, -0.6); self.latch = Part(2500, 9000, 25, 40)
    peak_db = -5
    def open_raw(self):
        b = z(0.12)
        put(b, 0.5 * hit(self.latch, 0.6, 0.08, 1, 1.2), 0.004)
        put(b, 0.25 * slide(0.010, 900, 5000, 1), 0.006)
        put(b, hit(self.curtain, 0.8, 0.25, 2, 2.2), 0.015)
        return b
    def close_raw(self):
        b = z(0.18)
        put(b, 0.25 * slide(0.010, 900, 5000, 1), 0.000)
        put(b, hit(self.curtain, 1.0, 0.25, 3, 2.4), 0.010)
        put(b, spring(1100, 0.1, 0.012), 0.012)
        return b
    def shutter(self, exp):
        b = z(0.2 + exp)
        put(b, 0.5 * hit(self.latch, 0.6, 0.08, 1, 1.2), 0.004)                         # release
        put(b, 0.25 * slide(0.010, 900, 5000, 1), 0.006)
        put(b, hit(self.curtain, 0.8, 0.25, 2, 2.2), 0.015)                              # first curtain lands
        put(b, 0.25 * slide(0.010, 900, 5000, 1), 0.006 + exp)
        put(b, hit(self.curtain, 1.0, 0.25, 3, 2.4), 0.016 + exp)                        # second curtain lands
        put(b, spring(1100, 0.1, 0.012), 0.018 + exp)
        return finish(mic(self.body(b), wet=0.14), -5)
    def wind(self):
        return lever_wind(self.body, Part(2000, 9000, 30, 35), 0.38, 22, softer=True)

class Compact:
    """Point-and-shoot (90s autofocus compact): leaf shutter in the lens, then the motor winds."""
    def __init__(self):
        self.body = Body([(320, 60, 1.0), (900, 140, .8), (2600, 400, .4)], 0.6)   # plastic shell
        self.blades = Part(2500, 10000, 30, 30); self.sol = Part(600, 3500, 20, 15)
    peak_db = -6
    def open_raw(self):
        b = z(0.1)
        put(b, 0.6 * hit(self.sol, 0.8, 0.25, 1, 1.5), 0.004)
        put(b, hit(self.blades, 0.6, 0.06, 1, 0.8), 0.010)
        return b
    def close_raw(self):
        b = z(0.12)
        put(b, hit(self.blades, 0.9, 0.06, 2, 0.9), 0.002)
        return b
    def shutter(self, exp):
        b = z(0.15 + exp)
        put(b, 0.6 * hit(self.sol, 0.8, 0.25, 1, 1.5), 0.004)                            # solenoid
        put(b, hit(self.blades, 0.6, 0.06, 1, 0.8), 0.010)                               # blades open
        put(b, hit(self.blades, 0.9, 0.06, 2, 0.9), 0.012 + exp)                          # blades close
        return finish(mic(self.body(b), wet=0.12), -6)
    def wind(self):
        return motor_wind(self.body)

def lever_wind(body, ratchet, T, teeth, softer=False):
    """Thumb lever: the pawl clicks over the ratchet (fast in the middle of the stroke), film and gears
    drag underneath, the shutter spring tightens, the lever hits its stop and springs back."""
    b = z(T + 0.25)
    s = np.linspace(0, 1, teeth)
    ts = 0.02 + T * (s - np.sin(2 * np.pi * s) / (2 * np.pi)) * rng.uniform(.97, 1.03)  # slow-fast-slow
    for k, t in enumerate(ts):
        put(b, hit(ratchet, rng.uniform(.35, .55) * (0.7 if softer else 1), 0.05, 1, 0.7, 0.05), t)
    put(b, slide(T, 300, 2500, 0.05 if softer else 0.08, grit=1.0), 0.02)              # film + gear drag
    tens = np.sin(2 * np.pi * np.cumsum(np.linspace(140, 230, int(T * SR))) / SR)      # spring being wound
    put(b, 0.012 * bp(tens, 100, 800) * np.linspace(0, 1, len(tens)), 0.02)
    put(b, hit(Part(800, 7000, 35, 30), 1.0, 0.15, 3, 2.5), 0.02 + T + 0.005)            # lever hits stop
    put(b, 0.5 * hit(Part(1500, 8000, 25, 30), 0.5, 0.1, 2, 2), 0.02 + T + 0.12)         # lever returns
    put(b, spring(950, 0.12, 0.015), 0.02 + T + 0.12)
    return finish(mic(body(b)), -7)

def motor_wind(body):
    """Small DC motor through a plastic gear train: spin-up, a load as the film pulls, a stop."""
    T = 0.55; n = int(T * SR); t = np.arange(n) / SR
    rpm = 180 + 120 * (1 - np.exp(-t / 0.03)) - 35 * np.clip((t - 0.4) / 0.12, 0, 1)
    rpm *= 1 + 0.03 * np.sin(2 * np.pi * 9 * t)                                    # film load wobble
    ph = 2 * np.pi * np.cumsum(rpm) / SR
    brush = (rng.random(n) < 0.02) * rng.standard_normal(n)                        # commutator crackle
    gear = np.zeros(n); teeth_ph = (ph * 11) % (2 * np.pi)
    gear[np.where(np.diff(teeth_ph) < 0)[0]] = 1                                   # each gear tooth meshing
    g = resonate(gear * 0.5, [2100, 3400, 5200], [300, 400, 600], [1, .6, .4])
    whine = 0.15 * np.sin(ph * 2) + 0.07 * np.sin(ph * 4)
    x = bp(whine, 200, 4000) + 0.35 * g + 0.4 * bp(brush, 3000, 9000)
    env = np.clip(t / 0.02, 0, 1) * np.clip((T - t) / 0.02, 0, 1)
    b = z(T + 0.15); b[:n] += x * env * 0.5
    put(b, hit(Part(600, 6000, 30, 25), 0.7, 0.2, 2, 2), T - 0.005)                # frame stop
    return finish(mic(body(b)), -9)

def save(path, x): wavfile.write(path, SR, (np.clip(x, -1, 1) * 32767).astype(np.int16))

if __name__ == '__main__':
    OUT = sys.argv[1]
    cams = {'slr': SLR(), 'rangefinder': Rangefinder(), 'compact': Compact()}
    for name, c in cams.items():
        for lab, e in [('1-500', 1 / 500), ('1-30', 1 / 30), ('half-second', 0.5)]:
            save(f'{OUT}/{name}-shutter-{lab}.wav', c.shutter(e))
        save(f'{OUT}/{name}-wind.wav', c.wind())
        # three shots in a row: shutter, wind; every one slightly different
        seq = z(4.6)
        for k in range(3):
            put(seq, c.shutter(1 / 125), 0.25 + 1.45 * k); put(seq, 0.85 * c.wind(), 0.25 + 1.45 * k + 0.45)
        save(f'{OUT}/{name}-three-shots.wav', finish(seq, -3))
    # dial detent: a tiny ball-bearing click into a notch
    tick = finish(mic(Body([(900, 200, 1)], 0.3)(hit(Part(3000, 12000, 25, 50), 1, 0.05, 2, 0.6, 0.05)), wet=0.05), -12)
    run = z(2.0)
    for i in range(6): put(run, finish(mic(hit(Part(3000, 12000, 25, 50), 1, 0.05, 2, 0.6, 0.05), wet=0.05), -12 - i % 2), 0.1 + i * 0.25)
    save(f'{OUT}/dial-tick.wav', tick); save(f'{OUT}/dial-six-stops.wav', run)

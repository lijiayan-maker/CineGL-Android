# How It Works & Design Trade-offs

[简体中文](原理与设计取舍.md) | English

> This is for anyone who wants to know what the GPU in this camera is actually busy with — including me, three months from now, after I've forgotten all of it.
> For usage and build commands, see the [README](../README.en.md). This page is only about *why*.

## The short version: the life of a frame

My rule for myself: **every pass has to map to something inside a real film camera**. Otherwise filter code slowly turns into superstition — you nudge one number, the whole image changes color, and nobody can say why.

```
Real world                          In CineGL
──────────                          ─────────
Lens       distortion, fringing,  → input pass (input_body.glsl)
           vignetting
Exposure   highlights scatter     → glow pass (Dual Kawase pyramid)
           into halation
Developing colors come out        → the 3D LUT in the film pass
Silver     grain                  → the noise in the film pass
Projection watching the film      → present pass (which also draws zebras, peaking and the waveform)
```

Ordering things this way means *whatever happens first physically gets computed first*. Halation is light bouncing around inside the film, so it belongs before color grading. You've never seen a film that gets developed first and only then starts glowing on its own.

---

## Why the core is C++

I originally wanted to do everything in Kotlin — just call `GLES30.glXxx` all the way down, nice and easy. Then it dawned on me: **the hardest part of GL code isn't the GL calls, it's the math in front of them** — coordinate transforms, LUT baking, how big each level of the blur pyramid should be. Debugging that on a phone means "change a line → build → install → squint at the screen", three minutes a round.

So I pulled all of it out into C++ under `core/`, split in two:

- `cinegl_cpu`: pure math, no GL. `core/scripts/run_host_tests.sh` runs a couple of hundred assertions on my laptop in two seconds;
- `cinegl_gles`: the part that actually talks to GL, kept as thin as possible. How big to draw and which parameters to use, it asks `PassPlan`.

The JNI layer (`cinegl_jni.cpp`) only converts types; not a single line of business logic lives there. So when something breaks, I run the unit tests on the laptop first and only go to the phone once they're green — most bugs never live long enough to meet a phone.

> The one bug that did make it to a phone: in `film.frag` I named both the halation texture and the halation strength `uHalation`. The GLSL compiler threw a fit on the spot, while the C++ tests looked on innocently — they never compile GLSL at all. Hence `validate_shaders.sh`, which compiles every shader on the laptop with the `glslc` that ships with the NDK. Lesson learned: **whatever you don't test, someone will test for you — usually the nearest phone.**

---

## Stop 1: the camera texture and that mysterious matrix

Camera2 pushes frames into a `SurfaceTexture`, and on the GL side you get a `GL_TEXTURE_EXTERNAL_OES` external texture. It has two quirks:

1. **It can only be sampled with `samplerExternalOES`.** The GraphicBuffer behind it may well be YUV, and the driver quietly converts YUV → RGB at sample time. Convenient, but it means you can't treat it like a normal texture (you can't attach it to an FBO, for example), so the first pass has to copy it into an ordinary RGBA texture before the rest of the pipeline can work with it.
2. **You must multiply by `getTransformMatrix()`.** That 4×4 matrix carries the rotation from the sensor's mounting angle, a vertical flip and the crop at the buffer edges. Skip it and you get a picture lying on its side — possibly upside down as well.

I multiply one more matrix after it, for the aspect-ratio crop:

```
texMatrix = stMatrix × cropMatrix
```

Read it right to left: first map frame coordinates to the crop region inside the upright image, then let the camera matrix map that onto the buffer. 16:9, 2.39:1 and 1:1 are all just `cropMatrix`; the GPU never draws a single extra pixel.

### Coordinate systems: where the bodies are buried

In the whole project I allow exactly one kind of image: **the upright picture you see when holding the phone vertically, long side vertical** (the comment at the top of `FrameGeometry.h` is the project's constitution). Three things then conspire to mess up orientation:

| Who | Its habit | What I do |
|---|---|---|
| GL texture coordinates | (0,0) at the bottom-left | The whole pipeline follows GL's convention |
| Bitmaps / JPEG | Row 0 at the top | Flip once in the photo input matrix (`stillInputMatrix`) |
| `glReadPixels` | Reads from the bottom row up | Flip once more when drawing before readback (`flipY` in `outputMatrix`) |

Every rotation, mirror and flip is written as an affine matrix from output coordinates to input coordinates, which three points pin down: `f(0,0)`, `f(1,0)` and `f(0,1)`. Derive it once, put it in a unit test, and whenever anyone has doubts, run the test. Far more reliable than drawing arrows on paper — I tried that, and by the third sheet I was questioning my life choices.

### Why recordings are always landscape

For portrait video, the obvious move is to feed the encoder a 1080×1920 portrait buffer. But plenty of hardware encoders aren't fond of width < height, and `isSizeSupported` may simply say no.

What I do: **the encoder buffer is always landscape (width ≥ height)**. When shooting portrait, I rotate the picture 90° clockwise into it and write a rotation flag into the MP4 so players turn it back — the phone's own camera app does the same. When shooting landscape, the buffer is already upright and the flag is 0, so even old players that ignore the flag can't get it wrong. (`planVideoOutput`; the unit tests walk through all four orientations.)

Photos don't play the metadata game; I just rotate the pixels. Anyone who has checked how consistently gallery apps honor the EXIF orientation tag knows why.

---

## Stop 2: what log is, and why I built two kinds

Log is a humble idea: **re-allocate code values along a logarithmic curve**. Our eyes are more sensitive to changes in the shadows, yet linear encoding spends half of its code values on the brightest stop. Log spreads the shadows out and squashes the highlights; what you record looks gray and washed out, but leaves much more room for grading later.

I defined a curve of my own called CineLog (`Color.cpp`). The key points:

```
linear 0    → 0
linear 0.18 → 0.454   (18% gray, a little above 0.4 like most log curves in the industry)
linear 1.0  → 0.752   (100% white, with a quarter of the code values still above it)
linear 4.0  → 1.0     (2 stops of headroom above white)
```

Why leave room above 1.0? Because halation gets added later. Halation is light *added* on top, which easily pushes values past 1.0. Rec.709 encoding chops everything off at 1.0, so highlights melt into a white blob; CineLog still has room for them, and the shoulder of the LUT eases them back down. **That's why halation looks much better with log on** — not magic, just enough code values.

Then the two implementations:

- **GPU emulation**: the camera outputs Rec.709 as usual; in the input pass I decode it back to linear and re-encode it as CineLog. The workflow is exactly the same as real log (flat image, LUT monitoring, recording the clean signal), but I have to be honest: **you don't get extra dynamic range for free**. Highlights the ISP has already clipped won't come back on the GPU. Its value is teaching, plus headroom for halation.
- **ISP curve**: Camera2 lets you replace the ISP's tone curve with `TONEMAP_MODE_CONTRAST_CURVE`. I sample CineLog into 64 points, put them in `TONEMAP_CURVE`, and the ISP outputs log directly. Inside the ISP the sensor's linear signal is still high precision, so applying the curve there and then quantizing to 8 bits is cleaner than wrangling it on the GPU after it's already 8-bit. Sadly not every phone supports it, and the emulator does the classic "message received, ignoring it": every point of the curve arrives in the request, and not a single pixel changes.

### Burning in the LUT vs monitoring only

The standard workflow on real cinema cameras: **record log, and apply a LUT on the monitor to preview the look**. I turned that into a switch:

- Burn-in on: both the screen and the file get the graded image;
- Burn-in off: the screen still shows the graded image, but the file gets the flat log.

It wasn't hard to implement, because every frame already has two textures: `signal` (after the lens, before grading) and `graded` (the finished image). Which one the encoder draws is decided by a single `Plane` enum. That's the payoff of splitting the pipeline into passes — the intermediate results are just lying there; take whichever one you need.

---

## Stop 3: 3D LUTs, and why I "bake" them

A look recipe is complicated: exposure, white balance, channel crosstalk, saturation, hue tweaks, an S-curve, split toning, bleach bypass, fading… Compute all of that for every pixel? A phone GPU would express its displeasure with fan noise (if it had a fan).

So I bake the recipe into a 33×33×33 lookup table on the CPU: compute each of the 35,937 grid points once and store them in a 3D texture. The GPU then does a single `texture(lut, rgb)` per pixel, with trilinear interpolation thrown in for free by the hardware. However complicated the recipe gets, the GPU cost stays the same.

A few details worth remembering:

1. **The half-texel offset.** Grid points sit at texel *centers*, but `texture()` coordinates 0 and 1 are the texture's *edges*. Use the color directly as the coordinate and both ends are off by half a cell. The correct version:

   ```glsl
   vec3 uvw = s * ((size - 1.0) / size) + 0.5 / size;
   ```

   Leave this line out and every look turns slightly gray — the sneakiest kind of bug, because it *looks fine-ish*.

2. **Toggling log means re-baking.** The table's input is the working signal. With log on, that signal is CineLog-encoded, so the table has to include "decode the log first". `LutBank` therefore remembers the current encoding; when it changes, every table is invalidated and re-baked in the background, and a generation counter throws away stale results so an old table can't sneak back in when you flip the switch back and forth quickly.

3. **Two tables: a neutral LUT and a look LUT.** "Look strength 0–10" doesn't bake a fainter table; the shader does `mix(neutral, look, strength)`. Dragging the slider never waits for the CPU, so it stays silky smooth.

4. **Imported `.cube` files expect Rec.709.** The vast majority of creative LUTs out there are 709 → 709, so with log on I first convert the log signal to 709 before feeding it in — and that step is baked into the table too, so the GPU never notices.

---

## Stop 4: halation and diffusion — Dual Kawase

Halation is an old flaw of film: strong light passes through the emulsion, bounces off the film base and exposes the bottom, red-sensitive layer a second time — hence the red-orange ring around highlights. Diffusion (think Pro-Mist filters) makes bright areas spill light outward and softens the overall contrast.

Both boil down to "blur the highlights and add them back". For the blur I chose **Dual Kawase**: each level halves the size, with 5 samples on the way down and 8 on the way up. More levels mean a bigger radius, but each level has only a quarter of the pixels of the previous one, so the total cost converges quickly. On phones, it's the best value for a large-radius blur that I've come across.

Things I insist on:

- **Add light in linear space.** Light adds up physically; adding sRGB-encoded values makes highlights gray and muddy. `glow_prefilter.frag` decodes before extracting highlights, and `film.frag` decodes before adding them back, then re-encodes.
- **Level 0 is always about 540 px on the short side.** The preview is 1080p; a photo may be 40 megapixels. If each pyramid started halving from its own resolution, the same "Halation 5" would look tiny in a photo. With a fixed starting point the blur radius is resolution-independent: what you see in the preview is what you get in the photo.
- **Blur results are half-float.** Linear highlights go above 1.0, which RGBA8 can't hold. If `RGBA16F` can be a render target (that needs `EXT_color_buffer_half_float`), I use it; if not, I fall back to RGBA8 — slightly worse results, but nothing breaks.
- **Upsampling writes back into the level above.** By then that level's downsampled result has served its purpose, so reusing it in place saves a whole set of buffers.

---

## Stop 5: grain

Grain is the easiest thing to get wrong — it tends to come out as TV static. Real silver-halide grain has three traits, and I take care of each one:

1. **It's clumpy**: instead of per-pixel white noise I use value noise (random numbers on grid points, smoothly interpolated inside each cell), at two frequencies;
2. **It's strongest in the midtones**: in pure black all the silver is gone and in pure white it's all there, so neither shows grain — the strength is multiplied by a parabola that peaks at 0.5;
3. **It moves every frame**: the random seed is the frame number.

For random numbers I use a PCG-style integer hash instead of the time-honored `fract(sin(x) * 43758.5453)`. The latter has different precision on different GPUs and produces regular stripes on some devices. Don't ask how I know. (Fine: a phone once greeted me with a screen full of diagonal lines.)

Grain size scales with "short side / 1080", for the same reason as halation.

---

## The lens's little flaws: vignetting and chromatic aberration

- **Vignetting** follows the cos⁴ law of natural vignetting: `brightness ∝ 1 / (1 + (r·k)²)²`. It looks far more like a real lens than an arbitrary `smoothstep` — the falloff only becomes noticeable near the edges, and most of the frame is barely touched. Coordinates are multiplied by the aspect ratio first, so the vignette stays round instead of being stretched into an olive by 2.39:1.
- **Chromatic aberration**: the R and B channels are shifted radially in opposite directions by a tiny amount, more so toward the edges. It costs just two extra samples in the input pass.

Both happen in linear space, because at heart they change the amount of light.

---

## Monitoring: things only for your eyes

Focus peaking, zebras and the waveform are drawn only in the present pass and **never end up in recordings or photos** — which is exactly why drawing to the screen gets a pass of its own instead of letting the film pass draw straight to the screen.

My favorite is the waveform, because the technique is a little counterintuitive: **a scatter plot made with the vertex shader**.

```
glDrawArrays(GL_POINTS, 0, w * h);   // no vertex data at all
```

Each point uses `gl_VertexID` to work out which pixel of the downscaled frame it stands for; its x is that pixel's column and its y is the pixel's brightness. Turn on additive blending, and the more points land in the same spot, the brighter it gets — there's your oscilloscope, and the CPU never touched a pixel. That's courtesy of `gl_VertexID` and `texelFetch` in ES 3.0; back in the ES 2.0 days you couldn't do it this cleanly.

---

## One frame, several destinations: using EGL properly

As far as EGL is concerned, the screen, the video encoder and an offscreen pbuffer are all just different `EGLSurface`s under the same context. Textures and FBOs belong to the context, so they're naturally shared among them. Each frame while recording goes like this:

```
makeCurrent(encoder surface) → draw (with rotation)                       → set timestamp → swap
makeCurrent(screen surface)  → draw (with rounded corners and monitoring) → swap
```

Two things that are easy to miss:

- The EGL config must include `EGL_RECORDABLE_ANDROID`, otherwise the encoder's Surface may refuse you.
- **Timestamps come from the camera sensor** (`SurfaceTexture.getTimestamp()`) and reach the encoder through `eglPresentationTimeANDROID`. On the audio side I use `AudioRecord.getTimestamp()` and pick the same clock as the camera's timestamp source (`BOOTTIME` for `REALTIME`, otherwise `MONOTONIC`). Subtract the first video frame's time on both sides, and audio and video line up. I measured it on the emulator: the first audio sample arrives about 40 ms after the first video frame, so the starts are aligned; at the end, audio stops about 0.1 s before the video, because stopping a recording first unhooks the microphone callback and then lets the video encoder flush its backlog.

---

## Photos: the same pipeline, one more time

The lazy way to take a photo is to grab a preview frame, but that's only 1080p. What I do instead: Camera2 captures a full-resolution JPEG → decode it to a bitmap → upload it as a 2D texture → **run the whole pipeline again on a separate set of full-resolution framebuffers** → draw it into an output FBO with the right orientation → read it back with `glReadPixels`.

Not a single shader line changes; the input pass just switches to its `sampler2D` variant (`input_2d.frag`). That's the payoff for insisting that effect parameters be resolution-independent: halation radius and grain size in a 40-megapixel photo match the preview exactly.

---

## Two things Camera2 doesn't give you

**Semi-auto exposure.** Camera2 exposure comes in two flavors only: fully automatic (AE) and fully manual. Want to lock ISO and leave the shutter on auto? Not on the menu. What I do: while AE is still running, I keep recording its `ISO × exposure time` (minus exposure compensation). When you switch to semi-auto, that product stays constant: you change the value you locked and the other one is derived from it, with the shutter also capped by the frame duration when recording. The catch: once locked, it doesn't follow changes in scene brightness. If you want it to, compute the average brightness on the GPU and feed it back — left as an exercise.

**White balance.** When the device supports `MANUAL_POST_PROCESSING`, I reuse the color matrix from AWB's last result and only adjust the R/B gains by color temperature, all inside the ISP. Without it, I lock AWB and multiply gains in linear space in the input pass. The temperature-to-gain conversion uses the mired difference (`1e6 / K`), with the slope taken from the rule of thumb "tungsten 3200K → daylight 5500K is about +131 mired, red gain ×0.65, blue gain ×1.67". Not exact, but it's monotonic, it feels right, and the gains are exactly 1 at 5500K — so the image doesn't jump the moment you switch from auto to manual.

---

## The reject pile: ideas I considered but didn't build

| Idea | Why not |
|---|---|
| Histogram with a compute shader | Needs ES 3.1 and SSBO atomics. The waveform is enough, and the scatter trick only needs 3.0 |
| Tetrahedral 3D LUT interpolation | More accurate than trilinear, but it has to be hand-written in the shader. Great learning value — so it's yours as an exercise |
| RGBA16F for the whole pipeline | Less banding, but twice the bandwidth; for now it's only used where it matters most, the glow pyramid |
| Photos via YUV_420_888 | Skips JPEG compression and teaches YUV → RGB along the way. Sadly "preview + max-size YUV" is only guaranteed on FULL devices, while JPEG is guaranteed even on LEGACY |
| CameraX | Convenient, but manual controls and tone curves need Camera2Interop workarounds — and this project is precisely about getting to know Camera2 |
| Live photos | Needs an always-on encoder with a ring buffer, then stitching the clip into the JPEG's XMP container. A good project, but it tests encoding, not GL |

---

## Exercises

Ordered from easy to hard; each one only touches a file or two:

1. **Add a sharpening filter**: do an unsharp mask at the end of the film pass (original + (original − blurred) × strength). Hint: you can borrow the diffusion pyramid for the blur.
2. **Tetrahedral interpolation**: hand-write tetrahedral LUT interpolation in `film.frag` and compare banding in gradients against hardware trilinear.
3. **Switch `signal`/`graded` to RGBA16F**, check the banding with a strong look, then use a GPU profiler to see what it costs in bandwidth.
4. **Histogram**: like the waveform, accumulate points with additive blending into a 256×1 `R16F` texture, then draw it.
5. **GPU metering**: shrink the frame to 1×1 (`glGenerateMipmap` or multi-level downsampling), read back the average brightness asynchronously through a PBO, and make semi-auto exposure follow the scene.
6. **RGB parade**: a three-channel version of the waveform, one channel each on the left, middle and right.

Finish these and you'll probably understand what the GPU in a camera is busy with better than most people. At least better than I did three months ago.

# Music fidelity and Concert safety

Concert is now a subtle filtered stereo room rather than a loud mono echo. Dry left/right
channels remain intact. The room uses four short preallocated damped feedback delays,
a 180 Hz high-pass send and a normalised decay; bass and vocals are less masked.
Intensity is proportional to the effect and 0% is neutral in every spatial mode.

Tonal preset curves are unchanged: Flat, Bass boost, Vocal, Pop, Rock, Dance, Hip-hop,
Jazz, Classical and Treble boost. Headroom now includes the combined EQ/bass response
and spatial gain. These settings can sound quieter than the old overdriven versions;
use output volume for listening level. Loudness matching remains a separate feature.

No GPU, convolution or additional playback stream was added. Delay buffers are reused;
unused stages are skipped. These are implementation properties, not measured device
battery claims. Android Auto, Bluetooth, phone speakers and headphones still require
listening and performance checks on hardware.

Regression checks cover all ten presets with extra bass, high-level sine distortion,
0% neutrality, stereo separation, mode changes and stale tails. Tones establish linearity
and peak safety for the tested cases; they do not replace music listening tests or prove
intersample true-peak protection.

Preset transitions now reserve a conservative combined-band gain before any boost ramps.
The preamp falls over 10 ms; filter ramps then start, and measured steady-state gain
returns after 300 ms of settling. Scheduling uses AudioParam automation, including when
the page is hidden. Rapid-retarget and all-preset transitions are tested with the limiter
disabled so overload cannot be hidden by compression.

The live browser context now calibrates headroom using unconnected native response probes
at its actual sample rate, cached until the EQ curve changes. Bands outside 45% of
the sample rate are skipped consistently with mRadio. Tests include 8/32/44.1/48/96 kHz,
actual Concert tail decay, and full-headroom tonal/spatial levels.

## Detailed fidelity and idle-power audit

- No periodic equalizer polling. Unused graph stages remain disconnected; response probes
  are unconnected and cached. Flat/Off before routing keeps Chromium's native path.
- Only routed media can wake a suspended equalizer. Hidden paused playback suspends
  immediately rather than relying on throttled page timers; detached media cannot
  keep it alive. Playing background audio remains enabled.
- Regression coverage includes 26 Web Audio behaviours plus 20 Kotlin policy checks.
  The pause/resume tests assert real suspend/resume requests, not just source strings.
- Physical Chromium/WebView, Bluetooth and Android Auto validation is still required.
  A sample ceiling is not oversampled true-peak limiting or proof of perceptual transparency.

Reference: https://www.w3.org/TR/webaudio/#dom-audiocontext-suspend

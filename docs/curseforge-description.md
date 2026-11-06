# Nvidium-vk

**Nvidium-vk brings Nvidium, the GPU-driven terrain renderer for Sodium, to NeoForge and to Minecraft's
Vulkan backend.** It replaces Sodium's terrain rendering on NVIDIA graphics cards: the whole world's
geometry stays in video memory, the GPU itself decides what is visible (mesh shaders, occlusion culling
against last frame's depth, one indirect draw for all terrain), and large render distances run at frame
rates vanilla Sodium cannot reach.

This is a fork of [Nvidium by Cortex](https://github.com/MCRcortex/nvidium), which is published under the
LGPL-3.0 license that permits forks. Nvidium-vk keeps the same license, credits the original author, and
is maintained independently. Everything below describes this fork.

## What is different from the original Nvidium

- **NeoForge instead of Fabric.** The original mod targets Fabric; Nvidium-vk is built for NeoForge 26.2
  and the NeoForge build of Sodium.
- **A Vulkan backend.** The original renders only through OpenGL with NVIDIA-specific extensions. Minecraft
  26.x can run on a Vulkan renderer, where those extensions do not exist; Nvidium-vk adds a second
  rendering backend built on `VK_EXT_mesh_shader` and buffer device addresses that plugs into Mojang's own
  Vulkan renderer. Both backends are included; the mod picks the one the game is running on.
- **Smaller video memory footprint.** The terrain arena starts small and grows on demand instead of
  claiming most of the free video memory at start-up.
- **Verified on both backends** with the Vulkan validation layers, a memory soak test and benchmarks (see
  the numbers below).

## Requirements

- An **NVIDIA graphics card, Turing (GTX 16xx / RTX 20xx) or newer**, with a current driver. Both
  backends need mesh shaders; the OpenGL backend also needs NVIDIA-only OpenGL extensions. The mod does
  not run on AMD or Intel graphics and disables itself there.
- Minecraft **26.2** with **NeoForge 26.2.0.93**.
- **Sodium 0.9.2+mc26.2** (NeoForge build), as a required dependency.
- To use the Vulkan backend, select Vulkan in Minecraft's *Video Settings*. The mod `neoloadingvulkanfix`
  is recommended there to avoid a start-up issue with NeoForge's loading screen.

## Installation and use

Put the Nvidium-vk jar into `mods/` next to Sodium. There is nothing to configure: at start-up the mod
checks the graphics card and the active graphics backend and writes `Enabling Nvidium` (or the missing
requirement) to the log. In the F3 debug screen the right column shows `Using nvidium renderer` together
with the GPU time of each rendering pass. Nvidium-vk's own options (video memory limit, fog, translucency
sorting, statistics) are on the *Nvidium* page of Sodium's video settings.

## Performance

Measured on an RTX 3070 Laptop at 1600×900 with vsync off, same world and camera for every row:

| Render distance | OpenGL, Sodium | OpenGL, Nvidium-vk | Vulkan, Sodium | Vulkan, Nvidium-vk |
|---:|---:|---:|---:|---:|
| 16 | 806 FPS | 850 FPS | 1444 FPS | 1380 FPS |
| 24 | 673 FPS | 733 FPS | 997 FPS | 1086 FPS |
| 32 | 588 FPS | 653 FPS | 752 FPS | 926 FPS |

With Distant Horizons (LOD radius 128, render distance 32): Vulkan with Sodium 286 FPS, with Nvidium-vk
303 FPS. The mod is made for large render distances; at render distance 16 its fixed per-frame work does
not pay off.

## Known limitations

- NVIDIA graphics cards only, by design.
- Pinned to NeoForge 26.2.0.93 and Sodium 0.9.2: the mod hooks into internals of both; other versions
  are untested and may fail to load.
- Shader packs (Iris) disable the mod, as in the original.
- Changing the statistics level takes effect after a renderer reload.

## Reporting problems

Questions and bug reports: please open an issue on GitHub (link below). Include `logs/latest.log`, the
graphics backend you use (OpenGL or Vulkan) and your driver version.

---

Source code and issue tracker: <https://github.com/Svffer/Nvidium-vk>
Support the development: <https://www.patreon.com/cw/svfr>
Original mod: [Nvidium by Cortex](https://github.com/MCRcortex/nvidium), LGPL-3.0.

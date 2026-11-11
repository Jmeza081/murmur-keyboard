# Third-party components

| Component | Use | License |
|---|---|---|
| [HeliBoard](https://github.com/Helium314/HeliBoard) 4.3-beta1 (`3e91b4f`) | Keyboard engine, vendored in `app/` | GPL-3.0 (some files Apache-2.0 / CC-BY-SA-4.0, see `LICENSE-*`) |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.3 | On-device speech runtime (`app/libs/`, fetched, not committed) | Apache-2.0 |
| [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) INT8 ONNX export | Speech model, downloaded in the app | CC-BY-4.0 (NVIDIA) |
| [Roboto Flex](https://github.com/googlefonts/roboto-flex) | Key label font, `app/src/main/res/font/roboto_flex.ttf` | SIL Open Font License 1.1 |
| Google gesture library `libjni_latinime.so` | Optional swipe typing, loaded by the user; never committed | Proprietary |

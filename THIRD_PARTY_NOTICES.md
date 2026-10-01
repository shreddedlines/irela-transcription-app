# Third-party notices

Irela includes the open-source components below, each under its own license.
No license is granted for Irela's own code.

## whisper.cpp

<https://github.com/ggml-org/whisper.cpp> — compiled into the Android native
library (`android/lib`), whose JNI bridge is adapted from whisper.cpp's Android
example.

```
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Inter typeface

<https://github.com/rsms/inter> — `android/app/src/main/res/font/`. Licensed
under the SIL Open Font License 1.1; the full text is in
`android/app/src/main/assets/licenses/Inter-OFL.txt`.

Other dependencies are downloaded by Gradle and pip at build time and are
covered by their own licenses.

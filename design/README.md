# Design

- [IDENTITY.md](IDENTITY.md): fonts, colors, shapes and rules for every visual.
- [PROMPTS.md](PROMPTS.md): prompts for the logo, the banner and the mockups, and for turning each image into a Sketch file.
- `logo.svg`: the logo master (1254 × 1254, editable layers), and `logo.png`, a 1024 × 1024 export. The launcher icon in `app/src/main/res/drawable/ic_launcher_*.xml` and the README logo (`docs/assets/img/icon.png`, cropped to a circle) are made from it. The rounded corners of its square are not used: the launcher applies its own mask.
- `screens/`: app screenshots for the mockups, in English, with a neutral status bar. The example track is "GLUCKSMANN LOVE" by Wailtoad and the example album is "The Dark Side of the Moon" by Pink Floyd. A playlist owner's username is blurred.
- [`spotifeur-preview.sketch`](spotifeur-preview.sketch): editable README banner and two mockups.
- [`spotifeur-instagram.sketch`](spotifeur-instagram.sketch): editable story and six carousel slides, in English.

Both Sketch documents are source files tracked in Git. They embed their screenshot
layers and the vector logo. Keep the existing fonts, palette, phone masks
and frame dimensions when replacing a screenshot or updating a description.

## README artwork

[`spotifeur-preview.sketch`](spotifeur-preview.sketch) contains three exportable frames:

| Frame | Size | Export |
| --- | --- | --- |
| Banner | 1280 × 640 | [banner.png](../docs/assets/img/banner.png) |
| Listening: home, player, lyrics, queue | 2400 × 1350 | [preview-listening.png](../docs/assets/img/preview-listening.png) |
| Library: album, playlist, settings, appearance | 2400 × 1350 | [preview-library.png](../docs/assets/img/preview-library.png) |

The artwork uses native vector shapes, gradients, shadows, live text and the
existing vector logo. Screenshot layers embed the original website captures from
`docs/assets/img/screens/`. Their aspect ratio and content are preserved. The
frames support PNG export at 1× and 2×.

[`build-preview.js`](build-preview.js) rebuilds the document and the three PNGs
through Sketch's scripting API. It loads the bundled Google Sans Flex font in
the Sketch process, with `ROND 100` and the site's `wght 780` for the main name.
The font is at [`app/src/main/res/font/gflex_variable.ttf`](../app/src/main/res/font/gflex_variable.ttf).

From Sketch 2025.3 or later, replace the repository path in this command:

```sh
/Applications/Sketch.app/Contents/MacOS/sketchtool run-script \
  'eval(String(NSString.stringWithContentsOfFile_encoding_error(context.repository + "/design/build-preview.js", 4, null)))' \
  --context='{"repository":"/path/to/Spotifeur"}' \
  --without-activating=YES
```

## Instagram artwork

[`spotifeur-instagram.sketch`](spotifeur-instagram.sketch) contains these frames:

| Frame | Size | Content |
| --- | --- | --- |
| `story` | 1080 × 1920 | App name, supported music sources, home and player |
| `carousel-01` | 1080 × 1350 | App overview |
| `carousel-02` | 1080 × 1350 | Queue and synced lyrics |
| `carousel-03` | 1080 × 1350 | Albums and playlists |
| `carousel-04` | 1080 × 1350 | Appearance and settings |
| `carousel-05` | 1080 × 1350 | Downloads, cache and quality |
| `carousel-06` | 1080 × 1350 | Installation |

The story keeps its jade shapes and lavender accent. Carousel slides use only a
dark green-to-black background gradient: `#082E21` at 0%, `#09130E` at 52%, and
`#030705` at 100%, running from top left to bottom right. All presentation text
is in English and describes the app without slogans. The original screenshot
content, including song titles and lyrics, stays unchanged.

The carousel grid uses 72–76 px text margins, 74 px titles, 31 px descriptions,
and Google Sans Flex with `ROND 100`. Page numbers and repository links keep
the same position on each slide. The shared identity is in [IDENTITY.md](IDENTITY.md).

[`build-instagram.js`](build-instagram.js) rebuilds the source document from the
repository's logo and screenshots. `--context.output` optionally exports all
seven frames as PNGs to a folder outside the repository:

```sh
/Applications/Sketch.app/Contents/MacOS/sketchtool run-script \
  'eval(String(NSString.stringWithContentsOfFile_encoding_error(context.repository + "/design/build-instagram.js", 4, null)))' \
  --context='{"repository":"/path/to/Spotifeur","output":"/path/to/exports"}' \
  --without-activating=YES
```

For direct edits, open the tracked Sketch source, duplicate a frame for a new
slide, and replace its screenshot image layer or live text. Save the source
back into `design/`. Export social PNGs outside the repository. To preserve a
direct edit when rebuilding, apply the same layout or copy change in the script.

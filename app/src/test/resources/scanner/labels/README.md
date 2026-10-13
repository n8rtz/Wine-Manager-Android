# Label OCR image fixtures

This is the destination for the original user-supplied JPEG fixtures used to evaluate the camera OCR pipeline. Keep the original filenames when adding them:

- `IMG20261013001626.jpg` — Reciente Gran Reserva, Bodegas Olarra, Rioja, 2019.
- `IMG20261013001611.jpg` — Blackstone Winery, Merlot, California; the word “Merlot” is in a calligraphic font.
- `IMG20261011143217.jpg` — group photo with the Rioja bottle centered among other bottles.

The images were visible in the chat, but their attached-file paths were not mounted in this workspace (`/home/user/uploads` does not exist here), so the JPEG binaries could not be copied or run through OCR in this turn. The parser regression tests use text transcribed from the visible labels; they are not a substitute for image-level OCR tests. Add the original JPEGs here once they are available to the workspace.

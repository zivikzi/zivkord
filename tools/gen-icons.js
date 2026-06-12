// turns the svg into the png + ico that electron-builder wants
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');
const pngToIco = require('png-to-ico');

const assets = path.join(__dirname, '..', 'assets');
const svg = path.join(assets, 'logo.svg');

(async () => {
  await sharp(svg).resize(512, 512).png().toFile(path.join(assets, 'logo.png'));

  const sizes = [16, 24, 32, 48, 64, 128, 256];
  const buffers = await Promise.all(
    sizes.map((s) => sharp(svg).resize(s, s).png().toBuffer())
  );
  fs.writeFileSync(path.join(assets, 'logo.ico'), await pngToIco(buffers));
  console.log('icons done');
})();

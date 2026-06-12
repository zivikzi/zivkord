const { app, BrowserWindow, session, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');

// Don't let Discord auto-pop the Windows passkey/security-key dialog.
app.commandLine.appendSwitch('disable-features', 'WebAuthenticationConditionalUI');

// In-memory session name (no "persist:" prefix) so nothing is written to disk
// and everything (login, last account, cookies) is gone when the app closes.
const PARTITION = 'zivkord';

// Pretend to be normal Chrome so Discord doesn't slam the door on us.
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

// The only places the Discord tab is allowed to phone home. Anything else gets
// dropped on the floor, so the app physically can't leak your data somewhere.
const ALLOWED = [
  'discord.com',
  'discordapp.com',
  'discordapp.net',
  'discord.media',
  'discordcdn.com',
  'discord.gg', // covers gateway.* and remote-auth-gateway.* (QR login)
];

function hostAllowed(url) {
  try {
    const h = new URL(url).hostname;
    return ALLOWED.some((d) => h === d || h.endsWith('.' + d));
  } catch {
    return false;
  }
}

function lockDownNetwork(ses) {
  ses.setUserAgent(UA);
  ses.webRequest.onBeforeRequest((details, cb) => {
    if (details.url.startsWith('devtools:') || details.url.startsWith('blob:') ||
        details.url.startsWith('data:') || hostAllowed(details.url)) {
      cb({ cancel: false });
    } else {
      cb({ cancel: true });
    }
  });
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1320,
    height: 860,
    minWidth: 1040,
    minHeight: 680,
    backgroundColor: '#1b1c22',
    title: 'ZiVKord',
    icon: path.join(__dirname, 'assets', 'logo.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webviewTag: true,
    },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'src', 'shell.html'));

  // Don't let stray links spawn random windows; send real ones to the OS browser.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (hostAllowed(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
}

// Keep the embedded Discord tab on a short leash: pin its preferences so an
// Electron upgrade can't quietly loosen them, refuse popups, and don't let it
// wander off Discord.
app.on('web-contents-created', (_e, contents) => {
  contents.on('will-attach-webview', (_evt, prefs) => {
    delete prefs.preload; // the Discord guest gets no preload of ours
    prefs.nodeIntegration = false;
    prefs.contextIsolation = true;
  });
  if (contents.getType() === 'webview') {
    contents.setWindowOpenHandler(({ url }) => {
      if (hostAllowed(url)) shell.openExternal(url);
      return { action: 'deny' };
    });
    contents.on('will-navigate', (e, url) => {
      if (!hostAllowed(url)) e.preventDefault();
    });
  }
});

ipcMain.handle('core-source', () =>
  fs.readFileSync(path.join(__dirname, 'src', 'inject', 'zivkord-core.js'), 'utf8')
);
ipcMain.handle('app-version', () => app.getVersion());

app.whenReady().then(() => {
  lockDownNetwork(session.fromPartition(PARTITION));
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

// Belt and suspenders: wipe anything the in-memory session might have touched
// before we exit, so no login crumbs are left behind.
app.on('before-quit', async (e) => {
  const ses = session.fromPartition(PARTITION);
  try { await ses.clearStorageData(); await ses.clearCache(); } catch (_) {}
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

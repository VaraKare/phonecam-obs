# Wi-Fi without a cable

PhoneCam offers three ways to reach OBS. The phone tries them all when you tap **Start live** and uses the first that works.

| Way | Needs | Speed (measured on the vivo V2437) |
|---|---|---|
| **USB cable** | USB debugging, once | ≈140 Mb/s (USB 2.0) |
| **Direct Wi-Fi** (this page) | One-time pairing by QR code or typed code. No Developer options. | Measured in the physical check |
| **Wireless debugging** | Developer options and USB approval | ≈50–65 Mb/s |

Direct Wi-Fi works on any Wi-Fi network the phone and Mac share, **with or without Internet**, and on the **phone's own hotspot** (the Mac joins the hotspot; turn mobile data off if you want). Video never leaves your local network. Android names the needed permission "Internet", but PhoneCam only ever connects to the Mac you paired.

The user approved the Android network permission for this on 11 November 2026, after Wireless debugging could not connect on their router.

## Using it

1. In OBS, open the PhoneCam source's **Properties** and click **Pair a phone over Wi-Fi**. For 3 minutes the source shows a QR code and a 16-character code. Hide the source from your stream while pairing: anyone who sees the code could pair.
2. On the phone, open **Settings → Pair with OBS (scan QR)** and point the camera at the code, or choose **Type pairing code**.
3. Done. From now on, tap **Start live** with the phone and Mac on the same Wi-Fi.

## How it works

The Mac is the listener: the plugin accepts connections on TCP port 47183 (or a free port if that is taken) and announces itself with Bonjour as `_phonecam._tcp` with TXT `v=1`, `id=<Mac id>` and `pair=1` while a pairing window is open. The phone connects out, to the Bonjour address or to the addresses it learned from the QR code.

### Pairing secret

The Mac creates 10 random bytes (80 bits) for each pairing window. They are shown as 16 characters from the alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (typed codes ignore case, spaces and dashes, and read I/L as 1 and O as 0), and inside the QR code:

```
phonecam://pair?v=1&id=<16 hex Mac id>&c=<16-character code>&p=<port>&a=<IPv4>,<IPv4>&n=<URL-encoded Mac name>
```

Both sides turn it into a key: `K = SHA-256("PhoneCam pairing code v1" || 10 bytes)`. The window closes after 3 minutes, after one successful pairing, or after 20 failed attempts.

### Handshake

All numbers are big-endian. P-256 public keys are 65-byte uncompressed points. HMAC is HMAC-SHA-256.

1. Phone → Mac, **ClientHello** (118 bytes): `"PCW1"`, mode (1 session, 2 pairing), Mac id (8), phone id (8), phone ephemeral public key (65), phone nonce (32).
2. Mac → phone, **ServerHello** (134 bytes): `"PCW1"`, status (0 OK; 1 unknown phone; 2 no pairing window; 3 busy), Mac ephemeral public key (65), Mac nonce (32), `tagM` (32). For a status other than 0, only the first 5 bytes are sent and the connection closes.
3. Phone → Mac, **ClientFinish**: `tagP` (32), name length (1), phone name (UTF-8, at most 64 bytes, pairing only).
4. Mac → phone: one byte, 0 accepted.

With `K` the pairing key (pairing mode) or the stored long-term key (session mode):

```
Z    = ECDH(phone ephemeral, Mac ephemeral)            (32-byte x coordinate)
PRK  = HMAC(K, Z)                                       (HKDF-Extract, salt K)
T1   = ClientHello || ServerHello without tagM
tagM = HMAC(PRK, "pcam mac"   || SHA-256(T1))
tagP = HMAC(PRK, "pcam phone" || SHA-256(T1 || tagM))
H    = SHA-256(T1 || tagM || tagP)
keys = HKDF-Expand(PRK, "pcam keys" || H, 64)           encryption key (32) || MAC key (32)
long-term key (pairing only) = HKDF-Expand(PRK, "pcam pairing" || H, 32)
```

Each side checks the other's tag in constant time before going on. Fresh ephemeral keys give every session new keys (forward secrecy), and the long-term key depends on the pairing's ephemeral exchange, so a photographed QR code is useless after pairing. A passive listener cannot test guesses of the code, because it never learns `Z`. An active attacker posing as the phone can test guesses offline against `tagM`, which is why the code is 80 bits rather than 6 digits.

### Video records

After the handshake the phone sends the same PCAM v3 messages as over USB ([USB live](USB_LIVE.md)), one per record:

```
length (u32, ciphertext bytes) || ciphertext || tag (16)
ciphertext = AES-256-CTR(encryption key, IV = record number (u64) || 8 zero bytes, message)
tag        = first 16 bytes of HMAC(MAC key, record number (u64) || length (u32) || ciphertext)
```

Record numbers start at 0 and count up, so a dropped, repeated, reordered or altered record fails the tag and ends the session. The Mac sends nothing after the handshake.

### Stored data

- Mac: `~/Library/Application Support/phonecam-obs/wifi.json` (mode 600): the Mac id and, for each paired phone, its id, name and long-term key. **Forget paired phones** in the source properties clears them.
- Phone: app-private storage, excluded from backups: the phone id and, for each paired Mac, its id, name, long-term key, port and last addresses. **Forget paired Macs** in Settings clears them.

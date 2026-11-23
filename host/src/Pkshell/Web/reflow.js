/*
 * PocketShell browser client: external PC console history + phone-width re-flow.
 * Port of the Android app's core/ConsoleHistory.kt, core/Reflow.kt, the parts of core/TerminalText.kt they use and
 * com.termux.terminal.WcWidth (Unicode 15 width tables). Same algorithms and constants; strings are UTF-16 like the
 * JVM (String.length / charAt / codePointAt behave the same), so cell widths, surrogate pairs (emoji) and caches match.
 *
 * Sets window.PsReflow = { ConsoleHistory, Reflow, WcWidth, TerminalText }.
 *
 * Public API (optional arguments in [brackets], defaults as in Kotlin):
 *
 *   WcWidth.width(codePoint) -> 0 | 1 | 2
 *
 *   TerminalText.strip(vt) -> string              escape sequences and control chars removed (\n, \t kept)
 *   TerminalText.join(rows[, maxLines = 5000]) -> string   rows trimEnd'ed, trailing blank rows dropped, newest maxLines
 *
 *   Reflow.MAX_SHRINK = 1.25, Reflow.MAX_GROW = 1.35
 *   new Reflow.Result(lines, cursorLine, cursorCol)        { lines: string[], cursorLine: int (-1 none), cursorCol: int (1-based) }
 *   new Reflow.RowInfo(width, indent, textStart, firstWord, lastWord, startsBlock, endsBlock
 *                      [, numbered = false, gutter = false, lastWordWide = false, headSign = " ", headSignCol = -1,
 *                       lead = "\u0000"])                  + getter .blank (indent >= width); headSign/lead are 1-char strings
 *   Reflow.layout(body, cols[, cursorCol = 0]) -> Result
 *   Reflow.layoutGroup(bodies, cols[, cursorRow = -1, cursorCol = 0]) -> Result
 *   Reflow.joins(prevInfo, nextInfo, pcCols) -> boolean
 *   Reflow.info(body) -> RowInfo
 *   Reflow.diffContinues(headInfo, rowInfo) -> boolean
 *   Reflow.headlessDiff(rowInfos, pcCols) -> boolean
 *   Reflow.fitCols(pcCols, phoneCols) -> int
 *   Reflow.lineCount(body, cols) -> int
 *
 *   new ConsoleHistory([maxLines = 3000])
 *     .accept(frame) -> int          frame: Uint8Array (UTF-8) or string. Lines scrolled into history, -1 = not a frame
 *     .replaceHistory(lines)         lines: string[] (the PC's scrollback)
 *     .clear()
 *     .plainText() -> string         history + window as plain text
 *     .maxScroll(viewRows[, cols = 0]) -> int
 *     .wrappedCount(n, cols) -> int  phone lines the last n history lines take at cols
 *     .render(viewRows, scroll[, cols = 0]) -> string   full redraw (VT text). Kotlin returns its UTF-8 bytes; here the
 *                                    string itself (xterm.js write() takes either; encode with TextEncoder if needed)
 *     .historySize (read-only int), .hasFrame (read-only boolean), .version (int, bumped on every content change;
 *     private in Kotlin), .pcCols (int, read/write; setting a new value clears the count cache and bumps version)
 *   ConsoleHistory.redrawnTail(tail, added) -> int
 *   ConsoleHistory.overlapWithTop(tail, window) -> int
 *   ConsoleHistory.scrollShift(prev, next) -> int
 */
(function () {
  "use strict";

  // ---------------------------------------------------------------- WcWidth (com.termux.terminal.WcWidth)

  // From https://github.com/jquast/wcwidth table_zero.py / table_wide.py (commit 1b9b6585, 2022-12-16), as in Termux.
  var ZERO_WIDTH = [
    [0x00300, 0x0036f], [0x00483, 0x00489], [0x00591, 0x005bd], [0x005bf, 0x005bf], [0x005c1, 0x005c2], [0x005c4, 0x005c5],
    [0x005c7, 0x005c7], [0x00610, 0x0061a], [0x0064b, 0x0065f], [0x00670, 0x00670], [0x006d6, 0x006dc], [0x006df, 0x006e4],
    [0x006e7, 0x006e8], [0x006ea, 0x006ed], [0x00711, 0x00711], [0x00730, 0x0074a], [0x007a6, 0x007b0], [0x007eb, 0x007f3],
    [0x007fd, 0x007fd], [0x00816, 0x00819], [0x0081b, 0x00823], [0x00825, 0x00827], [0x00829, 0x0082d], [0x00859, 0x0085b],
    [0x00898, 0x0089f], [0x008ca, 0x008e1], [0x008e3, 0x00902], [0x0093a, 0x0093a], [0x0093c, 0x0093c], [0x00941, 0x00948],
    [0x0094d, 0x0094d], [0x00951, 0x00957], [0x00962, 0x00963], [0x00981, 0x00981], [0x009bc, 0x009bc], [0x009c1, 0x009c4],
    [0x009cd, 0x009cd], [0x009e2, 0x009e3], [0x009fe, 0x009fe], [0x00a01, 0x00a02], [0x00a3c, 0x00a3c], [0x00a41, 0x00a42],
    [0x00a47, 0x00a48], [0x00a4b, 0x00a4d], [0x00a51, 0x00a51], [0x00a70, 0x00a71], [0x00a75, 0x00a75], [0x00a81, 0x00a82],
    [0x00abc, 0x00abc], [0x00ac1, 0x00ac5], [0x00ac7, 0x00ac8], [0x00acd, 0x00acd], [0x00ae2, 0x00ae3], [0x00afa, 0x00aff],
    [0x00b01, 0x00b01], [0x00b3c, 0x00b3c], [0x00b3f, 0x00b3f], [0x00b41, 0x00b44], [0x00b4d, 0x00b4d], [0x00b55, 0x00b56],
    [0x00b62, 0x00b63], [0x00b82, 0x00b82], [0x00bc0, 0x00bc0], [0x00bcd, 0x00bcd], [0x00c00, 0x00c00], [0x00c04, 0x00c04],
    [0x00c3c, 0x00c3c], [0x00c3e, 0x00c40], [0x00c46, 0x00c48], [0x00c4a, 0x00c4d], [0x00c55, 0x00c56], [0x00c62, 0x00c63],
    [0x00c81, 0x00c81], [0x00cbc, 0x00cbc], [0x00cbf, 0x00cbf], [0x00cc6, 0x00cc6], [0x00ccc, 0x00ccd], [0x00ce2, 0x00ce3],
    [0x00d00, 0x00d01], [0x00d3b, 0x00d3c], [0x00d41, 0x00d44], [0x00d4d, 0x00d4d], [0x00d62, 0x00d63], [0x00d81, 0x00d81],
    [0x00dca, 0x00dca], [0x00dd2, 0x00dd4], [0x00dd6, 0x00dd6], [0x00e31, 0x00e31], [0x00e34, 0x00e3a], [0x00e47, 0x00e4e],
    [0x00eb1, 0x00eb1], [0x00eb4, 0x00ebc], [0x00ec8, 0x00ece], [0x00f18, 0x00f19], [0x00f35, 0x00f35], [0x00f37, 0x00f37],
    [0x00f39, 0x00f39], [0x00f71, 0x00f7e], [0x00f80, 0x00f84], [0x00f86, 0x00f87], [0x00f8d, 0x00f97], [0x00f99, 0x00fbc],
    [0x00fc6, 0x00fc6], [0x0102d, 0x01030], [0x01032, 0x01037], [0x01039, 0x0103a], [0x0103d, 0x0103e], [0x01058, 0x01059],
    [0x0105e, 0x01060], [0x01071, 0x01074], [0x01082, 0x01082], [0x01085, 0x01086], [0x0108d, 0x0108d], [0x0109d, 0x0109d],
    [0x0135d, 0x0135f], [0x01712, 0x01714], [0x01732, 0x01733], [0x01752, 0x01753], [0x01772, 0x01773], [0x017b4, 0x017b5],
    [0x017b7, 0x017bd], [0x017c6, 0x017c6], [0x017c9, 0x017d3], [0x017dd, 0x017dd], [0x0180b, 0x0180d], [0x0180f, 0x0180f],
    [0x01885, 0x01886], [0x018a9, 0x018a9], [0x01920, 0x01922], [0x01927, 0x01928], [0x01932, 0x01932], [0x01939, 0x0193b],
    [0x01a17, 0x01a18], [0x01a1b, 0x01a1b], [0x01a56, 0x01a56], [0x01a58, 0x01a5e], [0x01a60, 0x01a60], [0x01a62, 0x01a62],
    [0x01a65, 0x01a6c], [0x01a73, 0x01a7c], [0x01a7f, 0x01a7f], [0x01ab0, 0x01ace], [0x01b00, 0x01b03], [0x01b34, 0x01b34],
    [0x01b36, 0x01b3a], [0x01b3c, 0x01b3c], [0x01b42, 0x01b42], [0x01b6b, 0x01b73], [0x01b80, 0x01b81], [0x01ba2, 0x01ba5],
    [0x01ba8, 0x01ba9], [0x01bab, 0x01bad], [0x01be6, 0x01be6], [0x01be8, 0x01be9], [0x01bed, 0x01bed], [0x01bef, 0x01bf1],
    [0x01c2c, 0x01c33], [0x01c36, 0x01c37], [0x01cd0, 0x01cd2], [0x01cd4, 0x01ce0], [0x01ce2, 0x01ce8], [0x01ced, 0x01ced],
    [0x01cf4, 0x01cf4], [0x01cf8, 0x01cf9], [0x01dc0, 0x01dff], [0x020d0, 0x020f0], [0x02cef, 0x02cf1], [0x02d7f, 0x02d7f],
    [0x02de0, 0x02dff], [0x0302a, 0x0302d], [0x03099, 0x0309a], [0x0a66f, 0x0a672], [0x0a674, 0x0a67d], [0x0a69e, 0x0a69f],
    [0x0a6f0, 0x0a6f1], [0x0a802, 0x0a802], [0x0a806, 0x0a806], [0x0a80b, 0x0a80b], [0x0a825, 0x0a826], [0x0a82c, 0x0a82c],
    [0x0a8c4, 0x0a8c5], [0x0a8e0, 0x0a8f1], [0x0a8ff, 0x0a8ff], [0x0a926, 0x0a92d], [0x0a947, 0x0a951], [0x0a980, 0x0a982],
    [0x0a9b3, 0x0a9b3], [0x0a9b6, 0x0a9b9], [0x0a9bc, 0x0a9bd], [0x0a9e5, 0x0a9e5], [0x0aa29, 0x0aa2e], [0x0aa31, 0x0aa32],
    [0x0aa35, 0x0aa36], [0x0aa43, 0x0aa43], [0x0aa4c, 0x0aa4c], [0x0aa7c, 0x0aa7c], [0x0aab0, 0x0aab0], [0x0aab2, 0x0aab4],
    [0x0aab7, 0x0aab8], [0x0aabe, 0x0aabf], [0x0aac1, 0x0aac1], [0x0aaec, 0x0aaed], [0x0aaf6, 0x0aaf6], [0x0abe5, 0x0abe5],
    [0x0abe8, 0x0abe8], [0x0abed, 0x0abed], [0x0fb1e, 0x0fb1e], [0x0fe00, 0x0fe0f], [0x0fe20, 0x0fe2f], [0x101fd, 0x101fd],
    [0x102e0, 0x102e0], [0x10376, 0x1037a], [0x10a01, 0x10a03], [0x10a05, 0x10a06], [0x10a0c, 0x10a0f], [0x10a38, 0x10a3a],
    [0x10a3f, 0x10a3f], [0x10ae5, 0x10ae6], [0x10d24, 0x10d27], [0x10eab, 0x10eac], [0x10efd, 0x10eff], [0x10f46, 0x10f50],
    [0x10f82, 0x10f85], [0x11001, 0x11001], [0x11038, 0x11046], [0x11070, 0x11070], [0x11073, 0x11074], [0x1107f, 0x11081],
    [0x110b3, 0x110b6], [0x110b9, 0x110ba], [0x110c2, 0x110c2], [0x11100, 0x11102], [0x11127, 0x1112b], [0x1112d, 0x11134],
    [0x11173, 0x11173], [0x11180, 0x11181], [0x111b6, 0x111be], [0x111c9, 0x111cc], [0x111cf, 0x111cf], [0x1122f, 0x11231],
    [0x11234, 0x11234], [0x11236, 0x11237], [0x1123e, 0x1123e], [0x11241, 0x11241], [0x112df, 0x112df], [0x112e3, 0x112ea],
    [0x11300, 0x11301], [0x1133b, 0x1133c], [0x11340, 0x11340], [0x11366, 0x1136c], [0x11370, 0x11374], [0x11438, 0x1143f],
    [0x11442, 0x11444], [0x11446, 0x11446], [0x1145e, 0x1145e], [0x114b3, 0x114b8], [0x114ba, 0x114ba], [0x114bf, 0x114c0],
    [0x114c2, 0x114c3], [0x115b2, 0x115b5], [0x115bc, 0x115bd], [0x115bf, 0x115c0], [0x115dc, 0x115dd], [0x11633, 0x1163a],
    [0x1163d, 0x1163d], [0x1163f, 0x11640], [0x116ab, 0x116ab], [0x116ad, 0x116ad], [0x116b0, 0x116b5], [0x116b7, 0x116b7],
    [0x1171d, 0x1171f], [0x11722, 0x11725], [0x11727, 0x1172b], [0x1182f, 0x11837], [0x11839, 0x1183a], [0x1193b, 0x1193c],
    [0x1193e, 0x1193e], [0x11943, 0x11943], [0x119d4, 0x119d7], [0x119da, 0x119db], [0x119e0, 0x119e0], [0x11a01, 0x11a0a],
    [0x11a33, 0x11a38], [0x11a3b, 0x11a3e], [0x11a47, 0x11a47], [0x11a51, 0x11a56], [0x11a59, 0x11a5b], [0x11a8a, 0x11a96],
    [0x11a98, 0x11a99], [0x11c30, 0x11c36], [0x11c38, 0x11c3d], [0x11c3f, 0x11c3f], [0x11c92, 0x11ca7], [0x11caa, 0x11cb0],
    [0x11cb2, 0x11cb3], [0x11cb5, 0x11cb6], [0x11d31, 0x11d36], [0x11d3a, 0x11d3a], [0x11d3c, 0x11d3d], [0x11d3f, 0x11d45],
    [0x11d47, 0x11d47], [0x11d90, 0x11d91], [0x11d95, 0x11d95], [0x11d97, 0x11d97], [0x11ef3, 0x11ef4], [0x11f00, 0x11f01],
    [0x11f36, 0x11f3a], [0x11f40, 0x11f40], [0x11f42, 0x11f42], [0x13440, 0x13440], [0x13447, 0x13455], [0x16af0, 0x16af4],
    [0x16b30, 0x16b36], [0x16f4f, 0x16f4f], [0x16f8f, 0x16f92], [0x16fe4, 0x16fe4], [0x1bc9d, 0x1bc9e], [0x1cf00, 0x1cf2d],
    [0x1cf30, 0x1cf46], [0x1d167, 0x1d169], [0x1d17b, 0x1d182], [0x1d185, 0x1d18b], [0x1d1aa, 0x1d1ad], [0x1d242, 0x1d244],
    [0x1da00, 0x1da36], [0x1da3b, 0x1da6c], [0x1da75, 0x1da75], [0x1da84, 0x1da84], [0x1da9b, 0x1da9f], [0x1daa1, 0x1daaf],
    [0x1e000, 0x1e006], [0x1e008, 0x1e018], [0x1e01b, 0x1e021], [0x1e023, 0x1e024], [0x1e026, 0x1e02a], [0x1e08f, 0x1e08f],
    [0x1e130, 0x1e136], [0x1e2ae, 0x1e2ae], [0x1e2ec, 0x1e2ef], [0x1e4ec, 0x1e4ef], [0x1e8d0, 0x1e8d6], [0x1e944, 0x1e94a],
    [0xe0100, 0xe01ef]
  ];
  var WIDE_EASTASIAN = [
    [0x01100, 0x0115f], [0x0231a, 0x0231b], [0x02329, 0x0232a], [0x023e9, 0x023ec], [0x023f0, 0x023f0], [0x023f3, 0x023f3],
    [0x025fd, 0x025fe], [0x02614, 0x02615], [0x02648, 0x02653], [0x0267f, 0x0267f], [0x02693, 0x02693], [0x026a1, 0x026a1],
    [0x026aa, 0x026ab], [0x026bd, 0x026be], [0x026c4, 0x026c5], [0x026ce, 0x026ce], [0x026d4, 0x026d4], [0x026ea, 0x026ea],
    [0x026f2, 0x026f3], [0x026f5, 0x026f5], [0x026fa, 0x026fa], [0x026fd, 0x026fd], [0x02705, 0x02705], [0x0270a, 0x0270b],
    [0x02728, 0x02728], [0x0274c, 0x0274c], [0x0274e, 0x0274e], [0x02753, 0x02755], [0x02757, 0x02757], [0x02795, 0x02797],
    [0x027b0, 0x027b0], [0x027bf, 0x027bf], [0x02b1b, 0x02b1c], [0x02b50, 0x02b50], [0x02b55, 0x02b55], [0x02e80, 0x02e99],
    [0x02e9b, 0x02ef3], [0x02f00, 0x02fd5], [0x02ff0, 0x02ffb], [0x03000, 0x0303e], [0x03041, 0x03096], [0x03099, 0x030ff],
    [0x03105, 0x0312f], [0x03131, 0x0318e], [0x03190, 0x031e3], [0x031f0, 0x0321e], [0x03220, 0x03247], [0x03250, 0x04dbf],
    [0x04e00, 0x0a48c], [0x0a490, 0x0a4c6], [0x0a960, 0x0a97c], [0x0ac00, 0x0d7a3], [0x0f900, 0x0faff], [0x0fe10, 0x0fe19],
    [0x0fe30, 0x0fe52], [0x0fe54, 0x0fe66], [0x0fe68, 0x0fe6b], [0x0ff01, 0x0ff60], [0x0ffe0, 0x0ffe6], [0x16fe0, 0x16fe4],
    [0x16ff0, 0x16ff1], [0x17000, 0x187f7], [0x18800, 0x18cd5], [0x18d00, 0x18d08], [0x1aff0, 0x1aff3], [0x1aff5, 0x1affb],
    [0x1affd, 0x1affe], [0x1b000, 0x1b122], [0x1b132, 0x1b132], [0x1b150, 0x1b152], [0x1b155, 0x1b155], [0x1b164, 0x1b167],
    [0x1b170, 0x1b2fb], [0x1f004, 0x1f004], [0x1f0cf, 0x1f0cf], [0x1f18e, 0x1f18e], [0x1f191, 0x1f19a], [0x1f200, 0x1f202],
    [0x1f210, 0x1f23b], [0x1f240, 0x1f248], [0x1f250, 0x1f251], [0x1f260, 0x1f265], [0x1f300, 0x1f320], [0x1f32d, 0x1f335],
    [0x1f337, 0x1f37c], [0x1f37e, 0x1f393], [0x1f3a0, 0x1f3ca], [0x1f3cf, 0x1f3d3], [0x1f3e0, 0x1f3f0], [0x1f3f4, 0x1f3f4],
    [0x1f3f8, 0x1f43e], [0x1f440, 0x1f440], [0x1f442, 0x1f4fc], [0x1f4ff, 0x1f53d], [0x1f54b, 0x1f54e], [0x1f550, 0x1f567],
    [0x1f57a, 0x1f57a], [0x1f595, 0x1f596], [0x1f5a4, 0x1f5a4], [0x1f5fb, 0x1f64f], [0x1f680, 0x1f6c5], [0x1f6cc, 0x1f6cc],
    [0x1f6d0, 0x1f6d2], [0x1f6d5, 0x1f6d7], [0x1f6dc, 0x1f6df], [0x1f6eb, 0x1f6ec], [0x1f6f4, 0x1f6fc], [0x1f7e0, 0x1f7eb],
    [0x1f7f0, 0x1f7f0], [0x1f90c, 0x1f93a], [0x1f93c, 0x1f945], [0x1f947, 0x1f9ff], [0x1fa70, 0x1fa7c], [0x1fa80, 0x1fa88],
    [0x1fa90, 0x1fabd], [0x1fabf, 0x1fac5], [0x1face, 0x1fadb], [0x1fae0, 0x1fae8], [0x1faf0, 0x1faf8], [0x20000, 0x2fffd],
    [0x30000, 0x3fffd]
  ];

  function intable(table, c) {
    if (c < table[0][0]) return false;
    var bot = 0;
    var top = table.length - 1;
    while (top >= bot) {
      var mid = (bot + top) >> 1;
      if (table[mid][1] < c) bot = mid + 1;
      else if (table[mid][0] > c) top = mid - 1;
      else return true;
    }
    return false;
  }

  /** Terminal display width of a code point: 0, 1 or 2. */
  function wcwidth(ucs) {
    if (ucs === 0 ||
        ucs === 0x034F ||
        (0x200B <= ucs && ucs <= 0x200F) ||
        ucs === 0x2028 ||
        ucs === 0x2029 ||
        (0x202A <= ucs && ucs <= 0x202E) ||
        (0x2060 <= ucs && ucs <= 0x2063)) {
      return 0;
    }
    // C0/C1 control characters (Termux: 0 instead of -1)
    if (ucs < 32 || (0x07F <= ucs && ucs < 0x0A0)) return 0;
    if (intable(ZERO_WIDTH, ucs)) return 0;
    return intable(WIDE_EASTASIAN, ucs) ? 2 : 1;
  }

  var WcWidth = { width: wcwidth };

  // ---------------------------------------------------------------- Kotlin helpers

  // Kotlin Char.isWhitespace(): Java isWhitespace (\t \n \v \f \r, \u001C-\u001F, space separators) or isSpaceChar
  // (Zs incl. no-break spaces, Zl, Zp). Differs from JS trim(): no \uFEFF, but \u001C-\u001F.
  var KT_WS = "\\t\\n\\u000B\\f\\r\\u001C-\\u001F\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
  var KT_TRIM_END = new RegExp("[" + KT_WS + "]+$");
  var KT_TRIM_START = new RegExp("^[" + KT_WS + "]+");
  function ktTrimEnd(s) { return s.replace(KT_TRIM_END, ""); }
  function ktTrim(s) { return ktTrimEnd(s).replace(KT_TRIM_START, ""); }

  function startsWith(s, prefix) { return s.lastIndexOf(prefix, 0) === 0; }

  function takeLast(list, n) { return n >= list.length ? list.slice() : list.slice(list.length - n); }

  function listEquals(a, b) {
    if (a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }

  function repeatChar(ch, n) {
    var s = "";
    for (var i = 0; i < n; i++) s += ch;
    return s;
  }

  // ---------------------------------------------------------------- TerminalText (strip, join)

  var ESCAPE = /\u001b\[[0-9;?<>=]*[ -\/]*[@-~]|\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)|\u001b[()][0-9A-Za-z]|\u001b[=>78DEHM]/g;

  var TerminalText = {
    /** [vt] without escape sequences and control characters (line breaks and tabs stay). */
    strip: function (vt) {
      var s = vt.replace(ESCAPE, "");
      var out = "";
      for (var i = 0; i < s.length; i++) {
        var c = s.charAt(i);
        if (c === "\r") continue;
        if (c === "\n" || c === "\t" || c >= " ") out += c;
      }
      return out;
    },
    /** Rows as text: trailing whitespace of each row and blank rows at the end removed, at most [maxLines] newest rows. */
    join: function (rows, maxLines) {
      if (maxLines === undefined) maxLines = 5000;
      var trimmed = rows.map(ktTrimEnd);
      var end = trimmed.length;
      while (end > 0 && trimmed[end - 1].length === 0) end--;
      return takeLast(trimmed.slice(0, end), maxLines).join("\n");
    }
  };

  // ---------------------------------------------------------------- Reflow

  function Result(lines, cursorLine, cursorCol) {
    this.lines = lines;
    this.cursorLine = cursorLine;
    this.cursorCol = cursorCol;
  }

  function RowInfo(width, indent, textStart, firstWord, lastWord, startsBlock, endsBlock, numbered, gutter, lastWordWide,
                   headSign, headSignCol, lead) {
    this.width = width;
    this.indent = indent;
    this.textStart = textStart;
    this.firstWord = firstWord;
    this.lastWord = lastWord;
    this.startsBlock = startsBlock;
    this.endsBlock = endsBlock;
    this.numbered = numbered === undefined ? false : numbered;
    this.gutter = gutter === undefined ? false : gutter;
    this.lastWordWide = lastWordWide === undefined ? false : lastWordWide;
    this.headSign = headSign === undefined ? " " : headSign;
    this.headSignCol = headSignCol === undefined ? -1 : headSignCol;
    this.lead = lead === undefined ? "\u0000" : lead;
  }
  Object.defineProperty(RowInfo.prototype, "blank", { get: function () { return this.indent >= this.width; } });

  function Cell(text, width, sgr, pcCol) {
    this.text = text;
    this.width = width;
    this.sgr = sgr;
    this.pcCol = pcCol;
    this.cursor = false;
  }
  // Kotlin: text == " " || text == " " (both literals are U+0020 in the source).
  Object.defineProperty(Cell.prototype, "space", { get: function () { return this.text === " " || this.text === " "; } });

  var FILLERS = "  \u2500\u2501\u2550-";
  var MARKERS = ["-", "*", "+", "\u2022", "\u25CF", "\u23FA", "\u23BF", "\u2514", "\u251C", ">", "\u276F", "\u273B", "\u203B",
    "\u2713", "\u2717"];
  var NUMBERED = /^\d{1,3}[.)]$/;
  var LINE_NUMBER = /^\d{1,5}$/;
  var WRAP_SLACK = 2;
  var LONG_TOKEN = 12;

  function sumWidth(cells, from, to) {
    var w = 0;
    for (var i = from; i < to; i++) w += cells[i].width;
    return w;
  }

  function isBox(text) {
    if (text.length === 0) return false;
    var c = text.charCodeAt(0);
    return c >= 0x2500 && c <= 0x259F;
  }

  function parse(body) {
    var cells = [];
    var sgr = "";
    var col = 0;
    var i = 0;
    while (i < body.length) {
      if (body.charAt(i) === "\u001b" && i + 1 < body.length && body.charAt(i + 1) === "[") {
        var j = i + 2;
        while (j < body.length) {
          var cc = body.charCodeAt(j);
          if (cc >= 0x40 && cc <= 0x7E) break;
          j++;
        }
        if (j < body.length && body.charAt(j) === "m") sgr = body.substring(i, j + 1);
        i = j + 1;
        continue;
      }
      var cp = body.codePointAt(i);
      var n = cp >= 0x10000 ? 2 : 1;
      var w = wcwidth(cp);
      var text = body.substring(i, i + n);
      if (w === 0 && cells.length > 0) {
        cells[cells.length - 1].text += text; // combining mark: stays with its base character
      } else {
        var width = w === 2 ? 2 : 1;
        cells.push(new Cell(text, width, sgr, col));
        col += width;
      }
      i += n;
    }
    return cells;
  }

  function infoCells(cells) {
    var end = cells.length;
    while (end > 0 && cells[end - 1].space) end--;
    var width = sumWidth(cells, 0, end);
    var k = 0;
    var indent = 0;
    while (k < end && cells[k].space) {
      indent += cells[k].width;
      k++;
    }
    var firstWord = 0;
    var m = k;
    var w = "";
    while (m < end && !cells[m].space) {
      firstWord += cells[m].width;
      w += cells[m].text;
      m++;
    }
    var lastWord = 0;
    var e = end - 1;
    var lastWordWide = false;
    while (e >= k && !cells[e].space) {
      lastWord += cells[e].width;
      if (cells[e].width === 2) lastWordWide = true;
      e--;
    }
    var numbered = LINE_NUMBER.test(w);
    var gutter = w.length >= 2 && (w.charAt(0) === "+" || w.charAt(0) === "-") && w.charAt(1) !== w.charAt(0);
    var isMarker = MARKERS.indexOf(w) >= 0 || NUMBERED.test(w) || numbered;
    var firstBox = k < end && isBox(cells[k].text);
    var lastBox = end > 0 && isBox(cells[end - 1].text);
    var textStart = indent;
    if (isMarker && m < end) {
      var col = indent + firstWord;
      var n = m;
      while (n < end && cells[n].space) {
        col += cells[n].width;
        n++;
      }
      textStart = col;
    }
    var headSign = " ";
    var headSignCol = -1;
    if (numbered && m + 1 < end && cells[m].space) {
      var t = cells[m + 1].text;
      if (t === "+" || t === "-" || t === " ") {
        headSign = t.charAt(0);
        headSignCol = indent + firstWord + 1;
      }
    }
    var lead = k < end ? cells[k].text.charAt(0) : "\u0000";
    return new RowInfo(width, indent, textStart, firstWord, lastWord, isMarker || firstBox, lastBox, numbered, gutter,
      lastWordWide, headSign, headSignCol, lead);
  }

  /** [info] of the first row of a headless diff run as if it were the numbered row (sign in its indent column). */
  function asDiffHead(i) {
    return new RowInfo(i.width, i.indent, i.textStart, i.firstWord, i.lastWord, i.startsBlock, i.endsBlock, i.numbered,
      i.gutter, i.lastWordWide, i.lead, i.indent, i.lead);
  }

  /** Marks the cell at PC column [target], padding with plain spaces when the cursor sits past the row's text. */
  function markCursor(cells, target) {
    for (var i = 0; i < cells.length; i++) {
      var it = cells[i];
      if (target >= it.pcCol && target < it.pcCol + it.width) {
        it.cursor = true;
        return;
      }
    }
    var last = cells.length > 0 ? cells[cells.length - 1] : null;
    var col = last !== null ? last.pcCol + last.width : 0;
    while (col < target) cells.push(new Cell(" ", 1, "", col++));
    var c = new Cell(" ", 1, "", col);
    c.cursor = true;
    cells.push(c);
  }

  /** Shortens the longest filler runs (keeping one cell of each) until the row fits [cols] or nothing is left. */
  function squeeze(cells, cols) {
    var excess = sumWidth(cells, 0, cells.length) - cols;
    while (excess > 0) {
      var bestStart = -1;
      var bestLen = 1;
      var i = 0;
      while (i < cells.length) {
        var c = cells[i];
        if (c.width !== 1 || c.text.length !== 1 || FILLERS.indexOf(c.text.charAt(0)) < 0) {
          i++;
          continue;
        }
        var j = i + 1;
        while (j < cells.length && cells[j].text === c.text && cells[j].sgr === c.sgr) j++;
        // Blanks only as padding before a box's right edge (or the row end): indentation inside text and code keeps
        // its width.
        if (c.space) {
          var textAfter = false;
          for (var x = j; x < cells.length; x++) {
            if (!cells[x].space && !isBox(cells[x].text)) {
              textAfter = true;
              break;
            }
          }
          if (textAfter) {
            i = j;
            continue;
          }
        }
        if (j - i > bestLen) {
          bestStart = i;
          bestLen = j - i;
        }
        i = j;
      }
      if (bestStart < 0) return;
      var cut = Math.min(excess, bestLen - 1);
      var from = bestStart + bestLen - cut; // drop the end of the run, keep where the text (and cursor) starts
      for (var y = from; y < bestStart + bestLen; y++) {
        if (cells[y].cursor) {
          cells[from - 1].cursor = true;
          break;
        }
      }
      cells.splice(from, cut);
      excess -= cut;
    }
  }

  /**
   * Wraps [cells] at [cols], breaking after a word when the line is at least a third full (else mid-word, as a
   * terminal would). Continuation lines start with [hang] blank cells.
   */
  function wrap(cells, cols, hang, textCol) {
    if (textCol === undefined) textCol = 0;
    var lines = [];
    var cursorLine = -1;
    var cursorCol = 0;
    var n = cells.length;
    var i = 0;
    for (;;) {
      var indent = lines.length === 0 ? 0 : hang;
      var avail = cols - indent;
      var w = 0;
      var j = i;
      while (j < n && w + cells[j].width <= avail) {
        w += cells[j].width;
        j++;
      }
      if (j === i && j < n) j++; // one cell wider than the room left: place it anyway
      var end = j;
      var next = j;
      if (j < n) {
        if (!cells[j].space || cells[j].cursor) {
          var k = j - 1;
          while (k > i && !(cells[k].space && !cells[k].cursor)) k--;
          var before = sumWidth(cells, i, k);
          // Never break inside the leading indent / marker / line number (or a code line's own indentation after it):
          // the first line must carry text.
          var col = 0;
          var text = false;
          for (var x = i; x < k; x++) {
            if (col >= textCol && !cells[x].space) {
              text = true;
              break;
            }
            col += cells[x].width;
          }
          if (k > i && before * 3 >= avail && (lines.length > 0 || text)) {
            end = k;
            next = k;
          }
        }
        while (next < n && cells[next].space && !cells[next].cursor) next++; // the break eats the spaces
      }
      var line = repeatChar(" ", indent);
      var sgr = "";
      var width = indent;
      for (var y = i; y < end; y++) {
        var c = cells[y];
        if (c.sgr !== sgr) {
          line += c.sgr.length === 0 ? "\u001b[0m" : c.sgr;
          sgr = c.sgr;
        }
        if (c.cursor) {
          cursorLine = lines.length;
          cursorCol = width + 1;
        }
        line += c.text;
        width += c.width;
      }
      lines.push(line);
      if (next >= n) break;
      i = next;
    }
    return new Result(lines, cursorLine, cursorCol);
  }

  /**
   * Joins a diff / code line cut over [rows] back into one: continuation rows lose their indent and sign, the pieces
   * are put together as they were cut (a space only where the cut ate one), then wrapped at [cols] under the text column.
   */
  function layoutDiff(head, rows, cols) {
    var content = head.headSignCol + 1;
    function rowWidth(r) {
      var end = r.length;
      while (end > 0 && r[end - 1].space && !r[end - 1].cursor) end--;
      return sumWidth(r, 0, end);
    }
    var widths = rows.map(rowWidth);
    var full = widths.length > 0 ? Math.max.apply(null, widths) : 0;
    var cells = rows[0].slice();
    for (var r = 1; r < rows.length; r++) {
      var part = rows[r].filter(function (it) { return it.pcCol >= content; });
      while (cells.length > 0 && cells[cells.length - 1].space && !cells[cells.length - 1].cursor) cells.pop();
      var short = full - widths[r - 1];
      var first = part.length > 0 ? part[0] : null;
      var last = cells.length > 0 ? cells[cells.length - 1] : null;
      // One cell short before a wide char: after a narrow char it was a space; between wide chars it is mostly the
      // middle of a Korean word, so no space.
      var ambiguous = short === 1 && first !== null && first.width === 2 && last !== null && last.width === 2;
      if (short > 0 && first !== null && !first.space && !ambiguous) cells.push(new Cell(" ", 1, "", -1));
      for (var q = 0; q < part.length; q++) cells.push(part[q]);
    }
    return wrap(cells, cols, Math.min(content, Math.floor(cols / 3)), content);
  }

  function joins(prev, next, pcCols) {
    if (pcCols <= 0 || prev.blank || next.blank) return false;
    if (prev.endsBlock || next.startsBlock) return false;
    if (prev.numbered || next.gutter) return false; // code: a wrapped code line keeps its own rows
    if (prev.width * 4 < pcCols * 3) return false;
    if (next.indent < prev.indent) return false;
    return prev.width + 1 + next.firstWord > pcCols - WRAP_SLACK;
  }

  function diffContinues(head, row) {
    if (head.headSignCol < 0 || row.blank || row.numbered) return false;
    return head.headSign === " " ? row.indent === head.headSignCol + 1
      : row.indent === head.headSignCol && row.lead === head.headSign;
  }

  function headlessDiff(rows, pcCols) {
    if (rows.length < 2 || pcCols <= 0) return false;
    var first = rows[0];
    if (first.indent < 4 || (first.lead !== "+" && first.lead !== "-")) return false;
    var full = rows[0].width;
    for (var i = 1; i < rows.length; i++) if (rows[i].width > full) full = rows[i].width;
    for (var k = 0; k < rows.length - 1; k++) {
      var it = rows[k];
      if (!(it.width >= full - WRAP_SLACK && it.width * 4 >= pcCols * 3)) return false;
    }
    return true;
  }

  function layoutGroup(bodies, cols, cursorRow, cursorCol) {
    if (cursorRow === undefined) cursorRow = -1;
    if (cursorCol === undefined) cursorCol = 0;
    if (cols <= 0) return new Result([bodies.join("")], cursorRow >= 0 ? 0 : -1, cursorCol);
    var first = parse(bodies[0]);
    if (cursorRow === 0 && cursorCol > 0) markCursor(first, cursorCol - 1);
    var third = Math.floor(cols / 3);
    if (bodies.length === 1) {
      var inf0 = infoCells(first);
      // A diff / code row's text starts after its sign (its own indentation is code, not a hanging indent).
      var textCol = inf0.blank ? 0 : (inf0.headSign !== " " && inf0.headSignCol >= 0) ? inf0.headSignCol + 1 : inf0.textStart;
      var hang0 = Math.min(textCol, third);
      squeeze(first, cols);
      return wrap(first, cols, hang0, textCol);
    }
    var head = infoCells(first);
    var r, row;
    if (head.headSignCol < 0 && !head.numbered && head.indent >= 4 && (head.lead === "+" || head.lead === "-")) {
      var dh = asDiffHead(head); // a headless diff run (ConsoleHistory only groups those that qualify)
      if (diffContinues(dh, info(bodies[1]))) head = dh;
    }
    if (head.headSignCol >= 0 && diffContinues(head, info(bodies[1]))) {
      var rows = [first];
      for (r = 1; r < bodies.length; r++) {
        row = parse(bodies[r]);
        if (cursorRow === r && cursorCol > 0) markCursor(row, cursorCol - 1);
        rows.push(row);
      }
      return layoutDiff(head, rows, cols);
    }
    var cells = first.slice();
    var prev = head;
    for (r = 1; r < bodies.length; r++) {
      row = parse(bodies[r]);
      if (cursorRow === r && cursorCol > 0) markCursor(row, cursorCol - 1);
      var inf = infoCells(row);
      while (cells.length > 0 && cells[cells.length - 1].space && !cells[cells.length - 1].cursor) cells.pop();
      // A long token (path, URL) cut at the window edge continues without a space; words get one back. Korean words
      // reach that many cells quickly (two per syllable) and are words all the same.
      if (prev.lastWord < LONG_TOKEN || prev.lastWordWide) cells.push(new Cell(" ", 1, "", -1));
      var k = 0;
      while (k < row.length && row[k].space && !row[k].cursor) k++;
      for (var i = k; i < row.length; i++) cells.push(row[i]);
      prev = inf;
    }
    var hang = Math.min(infoCells(parse(bodies[1])).indent, third);
    return wrap(cells, cols, hang);
  }

  function layout(body, cols, cursorCol) {
    if (cursorCol === undefined) cursorCol = 0;
    return layoutGroup([body], cols, cursorCol > 0 ? 0 : -1, cursorCol);
  }

  function info(body) { return infoCells(parse(body)); }

  var MAX_SHRINK = 1.25;
  var MAX_GROW = 1.35;

  function fitCols(pcCols, phoneCols) {
    if (pcCols <= phoneCols || phoneCols <= 0) return phoneCols;
    var lines = Math.floor(pcCols / phoneCols);
    // +1 per line when it wraps more than once: a wide char that would cross the edge moves to the next line.
    var target = Math.floor((pcCols + lines - 1) / lines) + (lines >= 2 ? 1 : 0);
    return target <= phoneCols * MAX_SHRINK ? target : phoneCols;
  }

  function lineCount(body, cols) { return layout(body, cols).lines.length; }

  var Reflow = {
    Result: Result,
    RowInfo: RowInfo,
    MAX_SHRINK: MAX_SHRINK,
    MAX_GROW: MAX_GROW,
    layout: layout,
    layoutGroup: layoutGroup,
    joins: joins,
    info: info,
    diffContinues: diffContinues,
    headlessDiff: headlessDiff,
    fitCols: fitCols,
    lineCount: lineCount
  };

  // ---------------------------------------------------------------- ConsoleHistory

  var START = "\u001b[?25l\u001b[H";
  var MAX_GROUP = 60;
  // Java's "." excludes the line terminators \n \r \u0085 \u2028 \u2029; JS "." would match \u0085.
  var ROW = /\u001b\[(\d+);1H([^\n\r\u0085\u2028\u2029]*?)\u001b\[0m\u001b\[K/g;
  // Java "$" (no MULTILINE) also matches before one final line terminator.
  var CURSOR = /\u001b\[(\d+);(\d+)H\u001b\[\?25h(?=(?:\r\n|[\n\r\u0085\u2028\u2029])?$)/;
  var SGR = /\u001b\[[0-9;]*m/g;
  var CACHE_MAX = 8000;
  var utf8 = typeof TextDecoder !== "undefined" ? new TextDecoder("utf-8") : null;

  function rowStart(y) { return "\u001b[" + y + ";1H\u001b[0m\u001b[K"; }
  var ROW_END = "\u001b[0m";

  /** String-keyed cache (Kotlin HashMap) without prototype keys. */
  function Cache() { this.clear(); }
  Cache.prototype.clear = function () { this.map = Object.create(null); this.size = 0; };
  Cache.prototype.getOrPut = function (key, make) {
    if (key in this.map) return this.map[key];
    var v = make();
    this.map[key] = v;
    this.size++;
    return v;
  };

  function ConsoleHistory(maxLines) {
    this.maxLines = maxLines === undefined ? 3000 : maxLines;
    this.history = [];
    this.window = [];
    /** 1-based [row, col] of the cursor in window, null when hidden. */
    this.cursor = null;
    this.version = 0;
    this._pcCols = 0;
    this._maxKey = null;
    this._maxValue = 0;
    this._countCache = new Cache();
    this._countCols = 0;
    this._infoCache = new Cache();
  }

  Object.defineProperty(ConsoleHistory.prototype, "historySize", { get: function () { return this.history.length; } });
  Object.defineProperty(ConsoleHistory.prototype, "hasFrame", { get: function () { return this.window.length > 0; } });
  Object.defineProperty(ConsoleHistory.prototype, "pcCols", {
    get: function () { return this._pcCols; },
    set: function (value) {
      if (this._pcCols !== value) {
        this._countCache.clear();
        this.version++;
      }
      this._pcCols = value;
    }
  });

  /** History and the current window as plain text (for copy / search). */
  ConsoleHistory.prototype.plainText = function () {
    return TerminalText.join(this.history.concat(this.window).map(TerminalText.strip));
  };

  /**
   * Takes one frame. Returns the number of lines that scrolled into history (0 if none), or -1 when [frame] is not a
   * console frame (the caller then shows it unchanged).
   */
  ConsoleHistory.prototype.accept = function (frame) {
    var text = typeof frame === "string" ? frame : utf8.decode(frame);
    if (!startsWith(text, START)) return -1;
    var rows = [];
    var m;
    ROW.lastIndex = 0;
    while ((m = ROW.exec(text)) !== null) rows.push([parseInt(m[1], 10), m[2]]);
    if (rows.length === 0) return -1;
    var height = 0;
    var i;
    for (i = 0; i < rows.length; i++) if (rows[i][0] > height) height = rows[i][0];
    var next = [];
    for (i = 0; i < height; i++) next.push("");
    for (i = 0; i < rows.length; i++) if (rows[i][0] >= 1) next[rows[i][0] - 1] = rows[i][1]; // row 0 throws in Kotlin
    var history = this.history;
    var win = this.window;
    var shifted = win.length === height ? ConsoleHistory.scrollShift(win, next) : 0;
    var added = shifted;
    if (shifted > 0) {
      // Claude Code re-renders a growing reply; a partly drawn copy that scrolled off earlier is replaced, not kept.
      var redrawn = ConsoleHistory.redrawnTail(takeLast(history, 60), win.slice(0, shifted));
      history.length -= redrawn;
      added -= redrawn;
      for (i = 0; i < shifted; i++) history.push(win[i]);
    }
    // The screen moved back down (the program's bottom area got shorter): the rows that came back at the top are the
    // last ones that scrolled off.
    if (shifted === 0 && win.length === height) {
      var back = ConsoleHistory.scrollShift(next, win);
      if (back >= 1 && back <= history.length && listEquals(takeLast(history, back), next.slice(0, back))) history.length -= back;
    }
    // Redraws can bring lines that already scrolled off back onto the screen: drop that overlap from the tail.
    history.length -= ConsoleHistory.overlapWithTop(takeLast(history, 40), next);
    if (history.length > this.maxLines) history.splice(0, history.length - this.maxLines);
    this.window = next;
    var c = CURSOR.exec(text);
    this.cursor = c !== null ? [parseInt(c[1], 10), parseInt(c[2], 10)] : null;
    this.version++;
    return Math.max(0, added);
  };

  /** The PC's scrollback for this console (sent on connect) replaces what the phone collected itself. */
  ConsoleHistory.prototype.replaceHistory = function (lines) {
    if (lines.length === 0 && this.history.length > 0) return; // a restarted host with nothing yet: keep the phone's
    this.history = takeLast(lines, this.maxLines);
    this.version++;
  };

  ConsoleHistory.prototype.clear = function () {
    this.history = [];
    this.window = [];
    this.cursor = null;
    this.version++;
  };

  /** Index (0-based, in history + window) of the last line worth showing at the bottom: content or cursor. */
  ConsoleHistory.prototype._liveBottom = function () {
    var lastContent = -1;
    for (var i = this.window.length - 1; i >= 0; i--) {
      if (this.window[i].length > 0) {
        lastContent = i;
        break;
      }
    }
    var cursorRow = (this.cursor !== null ? this.cursor[0] : 0) - 1;
    return this.history.length + Math.max(lastContent, cursorRow, 0);
  };

  /** Largest useful [scroll] for [viewRows] (0 = live view). [cols] > 0: re-flowed to that width (phone lines). */
  ConsoleHistory.prototype.maxScroll = function (viewRows, cols) {
    if (cols === undefined) cols = 0;
    if (cols <= 0) return Math.max(0, this._liveBottom() - (viewRows - 1));
    if (this.window.length === 0) return 0;
    var key = this._maxKey;
    if (key === null || key[0] !== this.version || key[1] !== viewRows || key[2] !== cols) {
      this._maxValue = Math.max(0, this._countRange(0, this._liveBottom(), cols) - viewRows);
      this._maxKey = [this.version, viewRows, cols];
    }
    return this._maxValue;
  };

  /** Phone lines the last [n] history lines take at [cols] (to keep a scrolled-back view in place). */
  ConsoleHistory.prototype.wrappedCount = function (n, cols) {
    if (cols <= 0) return n;
    return this._countRange(Math.max(0, this.history.length - n), this.history.length - 1, cols);
  };

  /** Phone lines rows [from]..[to] take, joined rows counted once per group (groups cut at [from]). */
  ConsoleHistory.prototype._countRange = function (from, to, cols) {
    var total = 0;
    var idx = to;
    while (idx >= from) {
      var s = Math.max(this._groupStart(idx), from);
      total += this._count(s, idx, cols);
      idx = s - 1;
    }
    return total;
  };

  ConsoleHistory.prototype._count = function (from, to, cols) {
    if (cols !== this._countCols || this._countCache.size > CACHE_MAX) {
      this._countCache.clear();
      this._countCols = cols;
    }
    var bodies = this._lines(from, to);
    return this._countCache.getOrPut(bodies.join("\n"), function () { return layoutGroup(bodies, cols).lines.length; });
  };

  ConsoleHistory.prototype._info = function (body) {
    if (this._infoCache.size > CACHE_MAX) this._infoCache.clear();
    return this._infoCache.getOrPut(body, function () { return info(body); });
  };

  /** First row of the logical line (joined PC rows) that row [idx] belongs to. */
  ConsoleHistory.prototype._groupStart = function (idx) {
    if (this._pcCols <= 0) return idx;
    var d = this._diffHead(idx);
    if (d >= 0) return d;
    var s = idx;
    while (s > 0 && idx - s < MAX_GROUP && joins(this._info(this._line(s - 1)), this._info(this._line(s)), this._pcCols)) s--;
    return s;
  };

  /**
   * First row of the diff / code line that row [idx] continues: its numbered row, or, when that has left the history,
   * the first of a run of rows with the same sign in the same column cut at the same width. -1 when none.
   */
  ConsoleHistory.prototype._diffHead = function (idx) {
    var row = this._info(this._line(idx));
    if (row.blank || row.numbered) return -1;
    var signed = row.lead === "+" || row.lead === "-";
    var k = idx;
    var it;
    while (k > 0 && idx - k < MAX_GROUP) {
      var above = this._info(this._line(k - 1));
      if (above.numbered) {
        if (above.headSignCol >= 0) {
          var all = true;
          for (it = k; it <= idx; it++) {
            if (!diffContinues(above, this._info(this._line(it)))) {
              all = false;
              break;
            }
          }
          if (all) return k - 1;
        }
        break;
      }
      if (above.blank || above.indent !== row.indent || (signed && above.lead !== row.lead)) break;
      k--;
    }
    if (k === idx) return -1;
    var infos = [];
    for (it = k; it <= idx; it++) infos.push(this._info(this._line(it)));
    return headlessDiff(infos, this._pcCols) ? k : -1;
  };

  /** Phone layout: the PC rows re-flowed to [cols], bottom-anchored like [render]. [scroll] counts phone lines. */
  ConsoleHistory.prototype._renderReflowed = function (viewRows, scroll, cols) {
    var c = this.cursor;
    var need = Math.max(scroll, 0) + viewRows;
    var lines = []; // bottom first
    var cursorFromBottom = -1;
    var cursorCol = 0;
    var idx = this.window.length === 0 ? -1 : this._liveBottom();
    var cursorIdx = c !== null ? this.history.length + c[0] - 1 : -1;
    while (idx >= 0 && lines.length < need) {
      var s = this._groupStart(idx);
      var cr = cursorIdx >= s && cursorIdx <= idx ? cursorIdx - s : -1;
      var r = layoutGroup(this._lines(s, idx), cols, cr, cr >= 0 ? c[1] : 0);
      idx = s;
      for (var k = r.lines.length - 1; k >= 0; k--) {
        if (k === r.cursorLine) {
          cursorFromBottom = lines.length;
          cursorCol = r.cursorCol;
        }
        lines.push(r.lines[k]);
      }
      idx--;
    }
    var sc = Math.min(Math.max(scroll, 0), Math.max(0, lines.length - viewRows));
    var sb = START;
    for (var row = 0; row < viewRows; row++) {
      var i = sc + viewRows - 1 - row;
      sb += rowStart(row + 1) + (i >= 0 && i < lines.length ? lines[i] : "") + ROW_END;
    }
    var fromTop = viewRows - 1 - (cursorFromBottom - sc);
    if (sc === 0 && cursorFromBottom >= 0 && fromTop >= 0 && fromTop < viewRows) {
      sb += "\u001b[" + (fromTop + 1) + ";" + cursorCol + "H\u001b[?25h";
    }
    return sb;
  };

  /**
   * Full redraw of [viewRows] phone rows showing the slice that ends [scroll] lines above the live bottom. The slice
   * is bottom-anchored; when there is less content than rows, the blank rows go on top. Every row is rewritten.
   */
  ConsoleHistory.prototype.render = function (viewRows, scroll, cols) {
    if (cols === undefined) cols = 0;
    if (cols > 0) return this._renderReflowed(viewRows, scroll, cols);
    var s = Math.min(Math.max(scroll, 0), this.maxScroll(viewRows));
    var end = this.window.length === 0 ? -1 : this._liveBottom() - s;
    var start = end - viewRows + 1;
    var sb = START;
    for (var r = 0; r < viewRows; r++) {
      var idx = start + r;
      var body = idx >= 0 && idx <= end ? this._line(idx) : "";
      sb += rowStart(r + 1) + body + ROW_END;
    }
    var c = this.cursor;
    if (s === 0 && c !== null) {
      var ci = this.history.length + c[0] - 1;
      if (ci >= start && ci <= end) sb += "\u001b[" + (ci - start + 1) + ";" + c[1] + "H\u001b[?25h";
    }
    return sb;
  };

  ConsoleHistory.prototype._line = function (idx) {
    return idx < this.history.length ? this.history[idx] : this.window[idx - this.history.length];
  };

  ConsoleHistory.prototype._lines = function (from, to) {
    var out = [];
    for (var i = from; i <= to; i++) out.push(this._line(i));
    return out;
  };

  /**
   * Lines at the end of [tail] that are an earlier render of the start of [added]: the first line equal (and at least
   * 4 visible characters), every later one equal or a prefix of the new line. 0 when there is none.
   */
  ConsoleHistory.redrawnTail = function (tail, added) {
    if (added.length === 0 || ktTrim(added[0].replace(SGR, "")).length < 4) return 0;
    for (var j = 0; j < tail.length; j++) {
      var n = tail.length - j;
      if (n > added.length || tail[j] !== added[0]) continue;
      var all = true;
      for (var it = 1; it < n; it++) {
        if (!startsWith(added[it], tail[j + it])) {
          all = false;
          break;
        }
      }
      if (all) return n;
    }
    return 0;
  };

  /** Largest m (>= 2, >= 2 non-blank) where the last m lines of [tail] equal the first m rows of [window]; else 0. */
  ConsoleHistory.overlapWithTop = function (tail, window) {
    for (var m = Math.min(tail.length, window.length); m >= 2; m--) {
      var part = tail.slice(tail.length - m);
      if (listEquals(part, window.slice(0, m))) {
        var nonBlank = 0;
        for (var i = 0; i < part.length; i++) if (part[i].length > 0) nonBlank++;
        if (nonBlank >= 2) return m;
      }
    }
    return 0;
  };

  /**
   * How many lines [next] scrolled up relative to [prev]: the smallest k whose top run next[i] == prev[i + k] covers at
   * least min(8, half the remaining rows) rows including 3 non-blank ones. 0 when the top did not move.
   */
  ConsoleHistory.scrollShift = function (prev, next) {
    var h = prev.length;
    if (h !== next.length || h < 4) return 0;
    function ok(k) {
      var n = 0;
      var nonBlank = 0;
      while (n + k < h && next[n] === prev[n + k]) {
        if (next[n].length > 0) nonBlank++;
        n++;
      }
      return n >= Math.min(8, Math.floor((h - k) / 2)) && nonBlank >= 3;
    }
    if (ok(0)) return 0;
    for (var k = 1; k < h; k++) if (ok(k)) return k;
    return 0;
  };

  window.PsReflow = {
    ConsoleHistory: ConsoleHistory,
    Reflow: Reflow,
    WcWidth: WcWidth,
    TerminalText: TerminalText
  };
})();

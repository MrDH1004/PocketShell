// Node port of android/app/src/test/.../ReflowTest.kt and ConsoleHistoryTest.kt for host/src/Pkshell/Web/reflow.js.
// Run: node host/tests/web/reflow.test.js
"use strict";

var test = require("node:test");
var assert = require("node:assert/strict");
var fs = require("node:fs");
var path = require("node:path");
var vm = require("node:vm");

var src = fs.readFileSync(path.join(__dirname, "..", "..", "src", "Pkshell", "Web", "reflow.js"), "utf8");
var sandbox = { window: {}, TextDecoder: TextDecoder };
vm.runInNewContext(src, sandbox, { filename: "reflow.js" });
var ConsoleHistory = sandbox.window.PsReflow.ConsoleHistory;
var Reflow = sandbox.window.PsReflow.Reflow;

var esc = "\u001b";
var enc = new TextEncoder();
function toBytes(s) { return enc.encode(s); }
function repeat(s, n) { return new Array(n + 1).join(s); }
// Arrays from the vm context have another Array prototype: compare as plain copies.
function arr(a) { return Array.prototype.slice.call(a); }
function eq(actual, expected) {
  if (Array.isArray(expected)) assert.deepStrictEqual(arr(actual), expected);
  else assert.strictEqual(actual, expected);
}
function strip(s) { return s.replace(new RegExp(esc + "\\[[0-9;]*m", "g"), ""); }
// Kotlin "." = any char but a line terminator.
var ROWS_RE = new RegExp(esc + "\\[(\\d+);1H" + esc + "\\[0m" + esc + "\\[K([^\\n\\r\\u0085\\u2028\\u2029]*?)" + esc + "\\[0m(?=" + esc + "\\[\\d+;\\d+H|$)", "g");
function rowsOf(out) {
  var list = [];
  var m;
  ROWS_RE.lastIndex = 0;
  while ((m = ROWS_RE.exec(out)) !== null) list.push(m[2]);
  return list;
}

// ------------------------------------------------------------------ ReflowTest

test("ReflowTest", function (t) {
  var red = esc + "[0;91;40m";
  var plain = esc + "[0;37;40m";

  t.test("shortRowIsUnchanged", function () {
    var r = Reflow.layout(plain + "hello", 10);
    eq(r.lines, [plain + "hello"]);
  });

  t.test("longTextWrapsAndCarriesColor", function () {
    var r = Reflow.layout(red + "abcdefgh", 3);
    eq(arr(r.lines).map(strip), ["abc", "def", "gh"]);
    eq(r.lines[1], red + "def"); // color continues on the wrapped line
  });

  t.test("windowWideRuleAndBoxShrinkToThePhone", function () {
    eq(arr(Reflow.layout(repeat("─", 120), 10).lines).map(strip), ["──────────"]);
    eq(arr(Reflow.layout("╭" + repeat("─", 118) + "╮", 10).lines).map(strip), ["╭────────╮"]);
    eq(arr(Reflow.layout("│ > hi" + repeat(" ", 113) + "│", 10).lines).map(strip), ["│ > hi   │"]);
  });

  t.test("wideCharsTakeTwoColumnsAndNeverSplit", function () {
    var r = Reflow.layout("가나다", 5);
    eq(r.lines, ["가나", "다"]);
  });

  t.test("cursorFollowsSqueezeAndWrap", function () {
    var box = Reflow.layout("│ > hi" + repeat(" ", 113) + "│", 10, 7);
    eq(box.cursorLine, 0);
    eq(box.cursorCol, 7);
    var wrapped = Reflow.layout("abcdef", 4, 7);
    eq(wrapped.cursorLine, 1);
    eq(wrapped.cursorCol, 3);
  });

  t.test("historyRendersReflowedBottomAnchored", function () {
    var h = new ConsoleHistory();
    var sb = esc + "[?25l" + esc + "[H";
    [repeat("x", 25), "> ab"].forEach(function (r, i) { sb += esc + "[" + (i + 1) + ";1H" + r + esc + "[0m" + esc + "[K"; });
    sb += esc + "[2;5H" + esc + "[?25h";
    h.accept(toBytes(sb));
    var out = h.render(5, 0, 10);
    eq(rowsOf(out).map(strip), ["", "xxxxxxxxxx", "xxxxxxxxxx", "xxxxx", "> ab "]); // the cursor cell past the text is a space
    eq(out.endsWith(esc + "[5;5H" + esc + "[?25h"), true);
    eq(h.maxScroll(5, 10), 0);
    eq(h.maxScroll(3, 10), 1);
  });

  t.test("pcWordWrappedRowsJoinAndRewrapWithHangingIndent", function () {
    var a = "- aaaa bbbb cccc dd";
    var b = "  eeee ffff";
    eq(Reflow.joins(Reflow.info(a), Reflow.info(b), 20), true);
    eq(Reflow.joins(Reflow.info(a), Reflow.info("- new item"), 20), false); // a new list item
    eq(Reflow.joins(Reflow.info("short line"), Reflow.info(b), 20), false); // not wrapped
    eq(Reflow.joins(Reflow.info("│ aaaa bbbb cccc  │"), Reflow.info("│ eeee"), 20), false); // box
    var r = Reflow.layoutGroup([a, b], 12);
    eq(arr(r.lines).map(strip), ["- aaaa bbbb", "  cccc dd", "  eeee ffff"]);
    // A path cut at the window edge continues without a space.
    var p = Reflow.layoutGroup(["Read src/main/java/com/x", "  /Reflow.kt"], 40);
    eq(arr(p.lines).map(strip), ["Read src/main/java/com/x/Reflow.kt"]);
  });

  t.test("codeAndDiffRowsKeepIndentationAndAreNotJoined", function () {
    var r = Reflow.layout("141      outside.nav = x", 20);
    eq(arr(r.lines).map(strip), ["141      outside.nav", "      = x"]);
    var code = "146 +   while (root != null && root.parent != blockerT.";
    eq(Reflow.joins(Reflow.info(code), Reflow.info("        +parent) root = root.parent;"), 56), false);
    eq(Reflow.joins(Reflow.info(repeat("x", 50)), Reflow.info("  +parent)"), 52), false);
  });

  t.test("claudeDiffLineCutAtItsOwnWidthIsJoinedAndFillsThePhone", function () {
    var added = [
      "     59 +aaaa bbbb cccc dddd eeee ffff gg",
      "        +gg hhhh iiii jjjj kkkk llll mmm",
      "        +nnnn"
    ];
    var r = Reflow.layoutGroup(added, 48);
    eq(arr(r.lines).map(strip), ["     59 +aaaa bbbb cccc dddd eeee ffff gggg hhhh", "         iiii jjjj kkkk llll mmm nnnn"]);
    // A row ending short of the others lost the space at the cut.
    var ate = Reflow.layoutGroup(["     59 +aaaa bbbb cccc dddd eeee ffff gg", "        +hhh iiii jjjj kkkk llll mmmm", "        +nn"], 80);
    eq(arr(ate.lines).map(strip), ["     59 +aaaa bbbb cccc dddd eeee ffff gghhh iiii jjjj kkkk llll mmmm nn"]);
    var spaced = Reflow.layoutGroup(["     59 +aaaa bbbb cccc dddd eeee ffff", "        +gg hhhh iiii jjjj kkkk llll mmm"], 80);
    eq(arr(spaced.lines).map(strip), ["     59 +aaaa bbbb cccc dddd eeee ffff gg hhhh iiii jjjj kkkk llll mmm"]);
    // Context line: the continuation starts at the text column.
    var ctx = Reflow.layoutGroup(["     60    글꼴 = 시스템 monospace / D2C", "         oding / JetBrains"], 48);
    eq(arr(ctx.lines).map(strip), ["     60    글꼴 = 시스템 monospace / D2Coding /", "         JetBrains"]);
    // A code line's own indentation is not left alone on the first line.
    var codeR = Reflow.layout("     12 -        h.accept(sb.toString().toByteArray())", 40);
    eq(strip(codeR.lines[0]), "     12 -        h.accept(sb.toString().");
    var head = Reflow.info(added[0]);
    eq(Reflow.diffContinues(head, Reflow.info(added[1])), true);
    eq(Reflow.diffContinues(head, Reflow.info("     60 +next line")), false); // its own number
    eq(Reflow.diffContinues(head, Reflow.info("        -removed")), false); // other sign
    eq(Reflow.diffContinues(head, Reflow.info("  ⎿ Done")), false); // other column
  });

  t.test("historyJoinsClaudeDiffRows", function () {
    var h = new ConsoleHistory();
    h.pcCols = 48;
    var sb = esc + "[?25l" + esc + "[H";
    [
      "     58  context",
      "     59 +aaaa bbbb cccc dddd eeee ffff gg",
      "        +gg hhhh",
      "     60 +next"
    ].forEach(function (r, i) { sb += esc + "[" + (i + 1) + ";1H" + r + esc + "[0m" + esc + "[K"; });
    h.accept(toBytes(sb));
    var out = h.render(3, 0, 48);
    eq(rowsOf(out).map(strip), ["     58  context", "     59 +aaaa bbbb cccc dddd eeee ffff gggg hhhh", "     60 +next"]);
  });

  t.test("historyJoinsWrappedRowsWhenPcWidthKnown", function () {
    var h = new ConsoleHistory();
    h.pcCols = 20;
    var sb = esc + "[?25l" + esc + "[H";
    ["- aaaa bbbb cccc dd", "  eeee ffff", "> x"].forEach(function (r, i) {
      sb += esc + "[" + (i + 1) + ";1H" + r + esc + "[0m" + esc + "[K";
    });
    h.accept(toBytes(sb));
    var out = h.render(4, 0, 12);
    eq(rowsOf(out).map(strip), ["- aaaa bbbb", "  cccc dd", "  eeee ffff", "> x"]);
    eq(h.maxScroll(4, 12), 0);
  });

  t.test("partlyDrawnReplyThatScrolledOffIsReplacedNotRepeated", function () {
    var tail = ["old", "● 세 기능 모두 가능", "지금 작업은 계속"];
    eq(ConsoleHistory.redrawnTail(tail, ["● 세 기능 모두 가능", "지금 작업은 계속"]), 2);
    eq(ConsoleHistory.redrawnTail(tail, ["● 세 기능 모두 가능", "지금 작업은 계속 진행하고", ""]), 2);
    eq(ConsoleHistory.redrawnTail(tail.concat([""]), ["● 세 기능 모두 가능", "지금 작업은 계속 진행", "", "1."]), 3);
    eq(ConsoleHistory.redrawnTail(tail, ["● 다른 내용", "x"]), 0);
    eq(ConsoleHistory.redrawnTail(["ok"], ["ok"]), 0);
  });

  t.test("fitColsAvoidsStrayWrappedCharacters", function () {
    eq(Reflow.fitCols(50, 53), 53); // fits: no change
    eq(Reflow.fitCols(56, 53), 56); // a bit wider: shrink so the row stays on one line
    eq(Reflow.fitCols(80, 53), 53); // 1.5x: shrinking that much is too small, wrap instead
    eq(Reflow.fitCols(240, 53), 61); // 4 lines of up to 61 instead of 4 full + 28 stray
    eq(Reflow.lineCount(repeat("가", 120), 61), 4);
  });

  t.test("koreanWordsJoinedAcrossRowsKeepTheirSpace", function () {
    var r = Reflow.layoutGroup(["  1. 지금은 흉내 낸 스크립트로만", "     확인했어."], 60);
    eq(arr(r.lines).map(strip), ["  1. 지금은 흉내 낸 스크립트로만 확인했어."]);
  });
});

// ------------------------------------------------------------------ ConsoleHistoryTest

test("ConsoleHistoryTest", function (t) {
  /** Same layout as host VtFrame.Build: rows 1..n, empty string = blank row. */
  function frame(rows, cursor) {
    var sb = esc + "[?25l" + esc + "[H";
    rows.forEach(function (r, i) { sb += esc + "[" + (i + 1) + ";1H" + r + esc + "[0m" + esc + "[K"; });
    if (cursor) sb += esc + "[" + cursor[0] + ";" + cursor[1] + "H" + esc + "[?25h";
    return toBytes(sb);
  }
  var shown = rowsOf;
  function cursorOf(out) {
    var m = new RegExp(esc + "\\[(\\d+);(\\d+)H" + esc + "\\[\\?25h").exec(out);
    return m ? m[1] + ";" + m[2] : null;
  }

  var screen1 = ["l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "> in", "status"];
  var screen2 = ["l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10", "> in", "status"];

  t.test("detectsScrollAndKeepsScrolledOffLines", function () {
    var h = new ConsoleHistory();
    eq(h.accept(frame(screen1, [9, 3])), 0);
    eq(h.accept(frame(screen2, [9, 3])), 2);
    eq(h.historySize, 2);
    // A repaint of the bottom only (input typed) is not a scroll.
    eq(h.accept(frame(screen2.slice(0, -2).concat(["> inx", "status"]), [9, 4])), 0);
    eq(h.historySize, 2);
  });

  t.test("extraPhoneRowsAreFilledWithHistory", function () {
    var h = new ConsoleHistory();
    h.accept(frame(screen1, [9, 3]));
    h.accept(frame(screen2, [9, 3]));
    var out = h.render(12, 0);
    eq(shown(out), ["l1", "l2"].concat(screen2));
    eq(cursorOf(out), "11;3");
  });

  t.test("fewerPhoneRowsShowTheBottomAndScrollBack", function () {
    var h = new ConsoleHistory();
    h.accept(frame(screen1, [9, 3]));
    h.accept(frame(screen2, [9, 3]));
    eq(shown(h.render(3, 0)), ["l10", "> in", "status"]);
    eq(h.maxScroll(3), 9);
    var up = h.render(3, 4);
    eq(shown(up), ["l6", "l7", "l8"]);
    eq(cursorOf(up), null); // no cursor while reading history
    eq(shown(h.render(3, 99)), ["l1", "l2", "l3"]); // clamped to the oldest line
  });

  t.test("shortContentIsBottomAnchoredAndRowsAreAlwaysCleared", function () {
    var h = new ConsoleHistory();
    h.accept(frame(["a", "b", "", "", ""], [2, 2]));
    var out = h.render(4, 0);
    eq(shown(out), ["", "", "a", "b"]);
    eq(cursorOf(out), "4;2");
    eq(shown(h.render(7, 0)), ["", "", "", "", "", "a", "b"]);
  });

  t.test("pcScrollbackReplacesPhoneHistory", function () {
    var h = new ConsoleHistory();
    h.replaceHistory(["old1", "old2"]);
    h.accept(frame(screen1, [9, 3]));
    eq(shown(h.render(12, 0)), ["old1", "old2"].concat(screen1));
    // The Kotlin test also checks ServerMessage.parse({"type":"history",...}), a different class not part of this port.
  });

  t.test("linesBackOnScreenAreDroppedFromHistory", function () {
    var h = new ConsoleHistory();
    h.replaceHistory(["old", "l1", "l2"]);
    h.accept(frame(screen1, [9, 3])); // l1, l2 are the window's top again
    eq(h.historySize, 1);
    eq(ConsoleHistory.overlapWithTop(["a", ""], ["a", "", "z"]), 0);
  });

  t.test("redrawWithNothingInCommonIsNotAScroll", function () {
    eq(ConsoleHistory.scrollShift(screen1, ["x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10"]), 0);
    var blank = ["", "", "", "", "", "", "", "", "", ""];
    eq(ConsoleHistory.scrollShift(blank, blank.slice()), 0);
  });

  t.test("aSingleLineThatComesBackIsNotShownTwice", function () {
    var h = new ConsoleHistory();
    h.accept(frame(screen1, [9, 3]));
    var up = ["l2", "l3", "l4", "l5", "l6", "l7", "l8", "busy", "> in", "status"];
    eq(h.accept(frame(up, [9, 3])), 1);
    eq(h.historySize, 1);
    eq(h.accept(frame(screen1, [9, 3])), 0);
    eq(h.historySize, 0);
    eq(shown(h.render(10, 0)), screen1);
    var twice = new ConsoleHistory();
    twice.accept(frame(["same", "same", "a", "b", "c", "d", "e", "f", "> in", "status"], [9, 3]));
    twice.accept(frame(["same", "a", "b", "c", "d", "e", "f", "g", "> in", "status"], [9, 3]));
    eq(twice.historySize, 1);
  });
});

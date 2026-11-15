using System.Text;
using Pkshell.Core.Sessions;

namespace Pkshell.Tests;

public class TerminalReportsTests
{
    private static bool Report(string s) => TerminalReports.IsOnlyReports(Encoding.UTF8.GetBytes(s));

    [Theory]
    [InlineData("\u001b[?1;2c")]                                   // DA1
    [InlineData("\u001b[>0;10;1c")]                                // DA2
    [InlineData("\u001b[12;40R")]                                  // cursor position
    [InlineData("\u001b[?0u")]                                     // kitty keyboard flags
    [InlineData("\u001b[?2026;2$y")]                               // DECRPM
    [InlineData("\u001b]11;rgb:0c0c/0c0c/0c0c\u001b\\")]           // OSC 11 background colour
    [InlineData("\u001b]10;rgb:cccc/cccc/cccc\u0007")]             // OSC 10, BEL-terminated
    [InlineData("\u001bP>|Windows Terminal\u001b\\")]              // XTVERSION
    [InlineData("\u001b[I")]                                       // focus in
    [InlineData("\u001b[?1;2c\u001b]11;rgb:0/0/0\u001b\\\u001b[O")] // several replies in one read
    public void RepliesAreNotTyping(string s) => Assert.True(Report(s));

    [Theory]
    [InlineData("a")]
    [InlineData(" ")]
    [InlineData("\r")]
    [InlineData("\u001b")]            // Esc key
    [InlineData("\u001b[A")]          // arrow up
    [InlineData("\u001b[3~")]         // Delete
    [InlineData("\u001b[97u")]        // kitty-encoded key
    [InlineData("\u001b[1;5C")]       // Ctrl+Right
    [InlineData("\u001b[?1;2cx")]     // a reply followed by a typed key
    [InlineData("\u001b[200~paste\u001b[201~")]
    [InlineData("")]
    public void KeysAreTyping(string s) => Assert.False(Report(s));
}

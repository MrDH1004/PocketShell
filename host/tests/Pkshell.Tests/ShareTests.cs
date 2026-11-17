using System.Net;
using Pkshell.Core.Auth;

namespace Pkshell.Tests;

public class ShareTests
{
    [Fact]
    public void GuestTokenReachesOneSessionUntilItExpires()
    {
        var now = DateTimeOffset.Parse("2026-11-17T10:00:00Z");
        var store = new DeviceStore(null, () => now);
        var auth = new TokenAuthenticator("LOCAL", store);
        var (guest, token) = store.AddGuest("friend phone", "abc123abc123", readOnly: true, now.AddHours(1), "flowdesk");

        var r = auth.Authenticate(token, IPAddress.Parse("192.168.0.20"));
        Assert.NotNull(r);
        Assert.True(r!.IsGuest);
        Assert.Equal("abc123abc123", r.SharedSession);
        Assert.True(r.ReadOnly);
        Assert.Equal("flowdesk", r.SessionTitle);
        Assert.Single(store.Guests("abc123abc123"));
        Assert.Empty(store.Guests("other"));

        now = now.AddHours(1);
        Assert.Null(auth.Authenticate(token, IPAddress.Parse("192.168.0.20")));
        Assert.False(store.IsActive(guest.Id));
        Assert.Empty(store.Guests());
    }

    [Fact]
    public void OwnerDevicesAreNotGuests()
    {
        var store = new DeviceStore(null);
        var (_, token) = store.Add("my phone");
        var r = new TokenAuthenticator("LOCAL", store).Authenticate(token, IPAddress.Parse("192.168.0.10"));
        Assert.False(r!.IsGuest);
        Assert.Empty(store.Guests());
    }

    [Fact]
    public void PruneEndsGuestsWhoseSessionIsGoneAndForgetsExpiredOnes()
    {
        var now = DateTimeOffset.Parse("2026-11-17T10:00:00Z");
        var store = new DeviceStore(null, () => now);
        var (alive, _) = store.AddGuest("a", "s-alive", false, now.AddHours(4), null);
        var (gone, _) = store.AddGuest("b", "s-gone", false, now.AddHours(4), null);
        var (old, _) = store.AddGuest("c", "s-alive", false, now.AddMinutes(1), null);
        store.Add("owner");
        now = now.AddMinutes(2);

        var ended = store.PruneGuests(sid => sid == "s-gone");
        Assert.Equal(new[] { gone.Id }, ended); // the expired one was already cut off by Validate; only forgotten here
        Assert.Equal(new[] { alive.Id }, store.Guests().Select(d => d.Id));
        Assert.Equal(2, store.List().Count); // owner + alive guest
        Assert.DoesNotContain(store.List(), d => d.Id == old.Id);
    }

    [Fact]
    public void InvitePinIsOneTimeAndExpires()
    {
        var now = DateTimeOffset.Parse("2026-11-17T10:00:00Z");
        var invites = new ShareInvites(() => now);
        var a = invites.Start("s1", readOnly: false, TimeSpan.FromHours(4), "proj");
        var b = invites.Start("s1", readOnly: true, TimeSpan.FromHours(1), "proj");
        Assert.NotEqual(a.Pin, b.Pin);
        Assert.Equal(2, invites.Open("s1").Count);

        var got = invites.TryConsume(a.Pin);
        Assert.NotNull(got);
        Assert.False(got!.ReadOnly);
        Assert.Null(invites.TryConsume(a.Pin)); // used

        now = now + ShareInvites.Lifetime;
        Assert.Null(invites.TryConsume(b.Pin)); // expired
        Assert.Empty(invites.Open());
    }

    [Fact]
    public void FiveWrongPinsCloseAllInvites()
    {
        var invites = new ShareInvites();
        var a = invites.Start("s1", false, TimeSpan.FromHours(1), null);
        var wrong = a.Pin == "000000" ? "111111" : "000000";
        for (int i = 0; i < ShareInvites.MaxWrongAttempts; i++) Assert.Null(invites.TryConsume(wrong));
        Assert.Null(invites.TryConsume(a.Pin));
    }

    [Fact]
    public void CancelTakesBackASessionsInvites()
    {
        var invites = new ShareInvites();
        invites.Start("s1", false, TimeSpan.FromHours(1), null);
        var other = invites.Start("s2", false, TimeSpan.FromHours(1), null);
        Assert.Equal(1, invites.Cancel("s1"));
        Assert.Equal(new[] { other.Pin }, invites.Open().Select(i => i.Pin));
        Assert.Throws<ArgumentOutOfRangeException>(() => invites.Start("s1", false, TimeSpan.FromDays(8), null));
    }

    [Theory]
    [InlineData("GET", "/api/info", true, true)]
    [InlineData("GET", "/api/events", true, true)]
    [InlineData("GET", "/api/sessions", true, true)]
    [InlineData("GET", "/api/sessions/s1", true, true)]
    [InlineData("GET", "/api/sessions/s1/screen", true, true)]
    [InlineData("GET", "/ws/sessions/s1", true, true)]
    [InlineData("GET", "/api/app/android/apk", true, true)]
    [InlineData("POST", "/api/sessions/s1/input", false, true)]
    [InlineData("POST", "/api/sessions/s1/input", true, false)]
    [InlineData("POST", "/api/clipboard/image", false, true)]
    [InlineData("POST", "/api/clipboard/image", true, false)]
    [InlineData("GET", "/api/sessions/s2", false, false)]
    [InlineData("GET", "/ws/sessions/s2", false, false)]
    [InlineData("POST", "/api/sessions/s2/input", false, false)]
    [InlineData("POST", "/api/sessions/s1/share", false, false)]
    [InlineData("DELETE", "/api/sessions/s1/share", false, false)]
    [InlineData("DELETE", "/api/sessions/s1", false, false)]
    [InlineData("POST", "/api/sessions/s1/end", false, false)]
    [InlineData("POST", "/api/sessions", false, false)]
    [InlineData("GET", "/api/files", false, false)]
    [InlineData("GET", "/api/files/content", false, false)]
    [InlineData("GET", "/api/shares", false, false)]
    [InlineData("POST", "/api/update/apply", false, false)]
    [InlineData("GET", "/ws/tunnel", false, false)]
    [InlineData("GET", "/api/devices", false, false)]
    [InlineData("GET", "/api/sessions/s1x", false, false)]
    public void GuestReachesOnlyItsSession(string method, string path, bool readOnly, bool allowed)
    {
        Assert.Equal(allowed, ShareAccess.Allows(method, path, "s1", readOnly));
    }
}

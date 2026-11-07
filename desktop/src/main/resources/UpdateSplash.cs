// The window a Windows update shows while Hamstr itself is gone: the start window's look
// (Splash.kt), with what is happening under the name. Updater's helper compiles it with
// Add-Type (Windows PowerShell, so C# 5), shows it before Hamstr quits and closes it once the
// new Hamstr has its own start window up, which sits in the same place.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

public class HamstrUpdateSplash : Form
{
    const int W = 300, H = 220;
    const int WS_EX_LAYERED = 0x80000, WS_EX_TOOLWINDOW = 0x80, WS_EX_TOPMOST = 0x8, WS_EX_NOACTIVATE = 0x8000000;

    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr hwnd);
    [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr hwnd, IntPtr dc);
    [DllImport("gdi32.dll")] static extern IntPtr CreateCompatibleDC(IntPtr dc);
    [DllImport("gdi32.dll")] static extern bool DeleteDC(IntPtr dc);
    [DllImport("gdi32.dll")] static extern IntPtr SelectObject(IntPtr dc, IntPtr obj);
    [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr obj);
    [DllImport("user32.dll")] static extern bool UpdateLayeredWindow(IntPtr hwnd, IntPtr dst, ref Point pos, ref Size size, IntPtr src, ref Point srcPos, int key, ref Blend blend, int flags);
    delegate bool EnumProc(IntPtr hwnd, IntPtr param);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc proc, IntPtr param);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    struct Blend { public byte Op, Flags, Alpha, Format; }

    static HamstrUpdateSplash current;
    static ManualResetEvent up;

    readonly float s;
    readonly Image icon;
    readonly Font title, status;
    readonly string text;
    readonly Color canvas, line, ink, muted, accent, track;
    readonly PrivateFontCollection fonts = new PrivateFontCollection();
    readonly List<IntPtr> fontMemory = new List<IntPtr>();
    readonly Stopwatch clock = Stopwatch.StartNew();
    readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();

    HamstrUpdateSplash(string dir, string text, int canvas, int line, int ink, int muted, int accent, int track)
    {
        this.text = text;
        this.canvas = Color.FromArgb(canvas);
        this.line = Color.FromArgb(line);
        this.ink = Color.FromArgb(ink);
        this.muted = Color.FromArgb(muted);
        this.accent = Color.FromArgb(accent);
        this.track = Color.FromArgb(track);
        using (Graphics g = Graphics.FromHwnd(IntPtr.Zero)) s = g.DpiX / 96f;
        // Read into memory, so Hamstr can delete the folder while this still shows.
        try { icon = Image.FromStream(new MemoryStream(File.ReadAllBytes(Path.Combine(dir, "icon.png")))); } catch { }
        title = LoadFont(Path.Combine(dir, "inter_600.ttf"), 22f, "Segoe UI Semibold");
        status = LoadFont(Path.Combine(dir, "inter_500.ttf"), 13f, "Segoe UI");

        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        int w = (int)Math.Round(W * s), h = (int)Math.Round(H * s);
        Rectangle area = Screen.PrimaryScreen.WorkingArea;
        Bounds = new Rectangle(area.X + (area.Width - w) / 2, area.Y + (area.Height - h) / 2, w, h);
        timer.Interval = 16;
        timer.Tick += delegate { Render(); };
    }

    Font LoadFont(string path, float px, string fallback)
    {
        try
        {
            byte[] bytes = File.ReadAllBytes(path);
            IntPtr mem = Marshal.AllocCoTaskMem(bytes.Length);
            Marshal.Copy(bytes, 0, mem, bytes.Length);
            fontMemory.Add(mem);
            int before = fonts.Families.Length;
            fonts.AddMemoryFont(mem, bytes.Length);
            FontFamily family = fonts.Families[fonts.Families.Length > before ? fonts.Families.Length - 1 : 0];
            foreach (FontStyle style in new[] { FontStyle.Regular, FontStyle.Bold })
                if (family.IsStyleAvailable(style)) return new Font(family, px * s, style, GraphicsUnit.Pixel);
        }
        catch { }
        return new Font(fallback, px * s, FontStyle.Regular, GraphicsUnit.Pixel);
    }

    protected override CreateParams CreateParams
    {
        get
        {
            CreateParams p = base.CreateParams;
            p.ExStyle |= WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_TOPMOST | WS_EX_NOACTIVATE;
            return p;
        }
    }

    protected override bool ShowWithoutActivation { get { return true; } }

    protected override void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        Render();
        timer.Start();
        up.Set();
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        timer.Stop();
        base.OnFormClosed(e);
    }

    static GraphicsPath Rounded(float x, float y, float w, float h, float r)
    {
        GraphicsPath p = new GraphicsPath();
        float d = r * 2;
        p.AddArc(x, y, d, d, 180, 90);
        p.AddArc(x + w - d, y, d, d, 270, 90);
        p.AddArc(x + w - d, y + h - d, d, d, 0, 90);
        p.AddArc(x, y + h - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    void Centered(Graphics g, string str, Font font, Color color, float top)
    {
        // Fonts are sized in pixels for the screen; the drawing is scaled, so undo it for the text.
        GraphicsState state = g.Save();
        g.ResetTransform();
        StringFormat f = StringFormat.GenericTypographic;
        SizeF size = g.MeasureString(str, font, int.MaxValue, f);
        using (Brush b = new SolidBrush(color)) g.DrawString(str, font, b, (Width - size.Width) / 2, top * s, f);
        g.Restore(state);
    }

    void Render()
    {
        int w = Width, h = Height;
        using (Bitmap bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb))
        {
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                g.TextRenderingHint = TextRenderingHint.AntiAlias;
                g.ScaleTransform(s, s);
                using (GraphicsPath shape = Rounded(0.5f, 0.5f, W - 1f, H - 1f, 20f))
                {
                    using (Brush b = new SolidBrush(canvas)) g.FillPath(b, shape);
                    using (Pen p = new Pen(line, 1f)) g.DrawPath(p, shape);
                }
                const int size = 72;
                if (icon != null) g.DrawImage(icon, (W - size) / 2, 40, size, size);
                // The same places as the start window, so the new Hamstr's takes over without a jump.
                Centered(g, "Hamstr", title, ink, 40 + size + 20);
                Centered(g, text, status, muted, 163);

                // A short accent bar gliding along a track: Hamstr is busy, not stuck.
                const int trackW = 120, barW = 36;
                float tx = (W - trackW) / 2f, ty = H - 26;
                using (Brush b = new SolidBrush(track)) using (GraphicsPath p = Rounded(tx, ty, trackW, 4, 2)) g.FillPath(b, p);
                double t = clock.Elapsed.TotalSeconds;
                double phase = (Math.Sin(t * Math.PI * 1.2) + 1) / 2;
                g.SetClip(new RectangleF(tx, ty, trackW, 4));
                using (Brush b = new SolidBrush(accent)) using (GraphicsPath p = Rounded(tx + (float)(phase * (trackW - barW)), ty, barW, 4, 2)) g.FillPath(b, p);
            }
            IntPtr screen = GetDC(IntPtr.Zero);
            IntPtr mem = CreateCompatibleDC(screen);
            IntPtr hbmp = bmp.GetHbitmap(Color.FromArgb(0));
            IntPtr old = SelectObject(mem, hbmp);
            Point pos = new Point(Left, Top), src = Point.Empty;
            Size sz = new Size(w, h);
            Blend blend = new Blend { Op = 0, Flags = 0, Alpha = 255, Format = 1 };
            UpdateLayeredWindow(Handle, screen, ref pos, ref sz, mem, ref src, 0, ref blend, 2);
            SelectObject(mem, old);
            DeleteObject(hbmp);
            DeleteDC(mem);
            ReleaseDC(IntPtr.Zero, screen);
        }
    }

    /** Shows the window on its own thread and returns once it is on screen (or couldn't be). */
    public static void Start(string dir, string text, int canvas, int line, int ink, int muted, int accent, int track)
    {
        up = new ManualResetEvent(false);
        Thread thread = new Thread(delegate()
        {
            try
            {
                SetProcessDPIAware();
                current = new HamstrUpdateSplash(dir, text, canvas, line, ink, muted, accent, track);
                Application.Run(current);
            }
            catch { }
            finally { up.Set(); }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;
        thread.Start();
        up.WaitOne(15000);
    }

    /** Waits until a program from [dir] shows a window (the new Hamstr's start window), [launcher] ends or time is up. */
    public static bool WaitForHamstr(string dir, int launcher, int timeoutMs)
    {
        string root = Path.GetFullPath(dir).TrimEnd('\\') + "\\";
        int me = Process.GetCurrentProcess().Id;
        Dictionary<uint, bool> ours = new Dictionary<uint, bool>();
        Stopwatch waited = Stopwatch.StartNew();
        while (waited.ElapsedMilliseconds < timeoutMs)
        {
            bool found = false;
            EnumWindows(delegate(IntPtr hwnd, IntPtr param)
            {
                if (!IsWindowVisible(hwnd)) return true;
                uint pid;
                GetWindowThreadProcessId(hwnd, out pid);
                if (pid == me) return true;
                bool mine;
                if (!ours.TryGetValue(pid, out mine))
                {
                    try { mine = Process.GetProcessById((int)pid).MainModule.FileName.StartsWith(root, StringComparison.OrdinalIgnoreCase); }
                    catch { mine = false; }
                    ours[pid] = mine;
                }
                if (mine) { found = true; return false; }
                return true;
            }, IntPtr.Zero);
            if (found) { Thread.Sleep(250); return true; }
            try { if (Process.GetProcessById(launcher).HasExited) return false; } catch { return false; }
            Thread.Sleep(100);
        }
        return false;
    }

    public static void Finish()
    {
        HamstrUpdateSplash c = current;
        if (c == null) return;
        try { c.Invoke((MethodInvoker)delegate { c.Close(); }); } catch { }
    }
}

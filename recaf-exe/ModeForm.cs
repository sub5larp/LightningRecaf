// Mode chooser shown before launching: plain latest Recaf, or the custom build with the LightningRecaf plugin.

using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Reflection;
using System.Windows.Forms;

namespace RecafExe
{
    class ModeForm : Form
    {
        static readonly Color Background = Color.FromArgb(12, 12, 12);
        static readonly Color Card = Color.FromArgb(25, 25, 25);
        static readonly Color CardHover = Color.FromArgb(38, 38, 38);
        static readonly Color AccentA = Color.FromArgb(110, 110, 110);
        static readonly Color AccentB = Color.FromArgb(235, 235, 235);
        static readonly Color TitleColor = Color.FromArgb(240, 240, 240);
        static readonly Color MutedColor = Color.FromArgb(160, 160, 160);

        public string Choice;          // "latest" or "custom", null when cancelled
        public bool Remember;
        readonly float scale;
        readonly Image logo;
        readonly CheckBox remember = new CheckBox();
        readonly Panel latestCard, customCard;

        public ModeForm(string defaultMode, string fileToOpen)
        {
            using (var g = Graphics.FromHwnd(IntPtr.Zero)) scale = g.DpiX / 96f;
            Text = "LightningRecaf";
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Background;
            ForeColor = TitleColor;
            ClientSize = new Size(S(560), S(300));
            DoubleBuffered = true;
            KeyPreview = true;
            ShowInTaskbar = true;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("logo.png"))
                if (s != null) logo = Image.FromStream(s);

            var title = new Label
            {
                Text = fileToOpen == null ? "Choose your workbench" : "Open " + fileToOpen + " with...",
                Font = new Font("Segoe UI Semibold", 14f),
                ForeColor = TitleColor,
                AutoSize = false,
                TextAlign = ContentAlignment.MiddleLeft,
                Location = new Point(S(90), S(22)),
                Size = new Size(S(450), S(32)),
            };
            var subtitle = new Label
            {
                Text = "Choose the latest upstream build or your custom reversing workbench.",
                Font = new Font("Segoe UI", 9f),
                ForeColor = MutedColor,
                AutoSize = false,
                Location = new Point(S(90), S(54)),
                Size = new Size(S(450), S(20)),
            };
            Controls.Add(title);
            Controls.Add(subtitle);

            latestCard = MakeCard("1", "Latest Recaf", "The newest official snapshot, nothing added.",
                new Point(S(24), S(96)), () => Pick("latest"));
            customCard = MakeCard("2", "LightningRecaf", "Tested engine + automatic deobfuscation, evidence, three bytecode views,\ngrayscale themes and workspace explorer tabs.",
                new Point(S(288), S(96)), () => Pick("custom"));
            Controls.Add(latestCard);
            Controls.Add(customCard);

            remember.Text = "Remember my choice (Shift or --ask asks again)";
            remember.ForeColor = MutedColor;
            remember.Font = new Font("Segoe UI", 9f);
            remember.Location = new Point(S(24), S(252));
            remember.Size = new Size(S(330), S(24));
            remember.FlatStyle = FlatStyle.Flat;
            Controls.Add(remember);

            var hint = new Label
            {
                Text = "Enter = " + (defaultMode == "custom" ? "Custom" : "Latest") + "   Esc = cancel",
                Font = new Font("Segoe UI", 8.5f),
                ForeColor = MutedColor,
                AutoSize = false,
                TextAlign = ContentAlignment.MiddleRight,
                Location = new Point(S(360), S(254)),
                Size = new Size(S(176), S(20)),
            };
            Controls.Add(hint);

            KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.D1 || e.KeyCode == Keys.NumPad1) Pick("latest");
                else if (e.KeyCode == Keys.D2 || e.KeyCode == Keys.NumPad2) Pick("custom");
                else if (e.KeyCode == Keys.Enter) Pick(defaultMode == "custom" ? "custom" : "latest");
                else if (e.KeyCode == Keys.Escape) Close();
            };
            MouseDown += DragWindow;
        }

        int S(int v) { return (int)Math.Round(v * scale); }

        Panel MakeCard(string number, string heading, string description, Point location, Action onClick)
        {
            var card = new Panel { Location = location, Size = new Size(S(248), S(140)), BackColor = Card, Cursor = Cursors.Hand };
            var badge = new Label
            {
                Text = number, Font = new Font("Segoe UI Semibold", 11f), ForeColor = Color.White, BackColor = AccentA,
                AutoSize = false, TextAlign = ContentAlignment.MiddleCenter, Location = new Point(S(12), S(12)), Size = new Size(S(26), S(26)),
            };
            var head = new Label
            {
                Text = heading, Font = new Font("Segoe UI Semibold", 11.5f), ForeColor = TitleColor, AutoSize = false,
                Location = new Point(S(46), S(12)), Size = new Size(S(190), S(26)), TextAlign = ContentAlignment.MiddleLeft,
            };
            var desc = new Label
            {
                Text = description, Font = new Font("Segoe UI", 8.5f), ForeColor = MutedColor, AutoSize = false,
                Location = new Point(S(12), S(46)), Size = new Size(S(224), S(84)),
            };
            foreach (Control c in new Control[] { badge, head, desc }) { c.Click += (s, e) => onClick(); c.Cursor = Cursors.Hand; }
            card.Controls.AddRange(new Control[] { badge, head, desc });
            card.Click += (s, e) => onClick();
            EventHandler enter = (s, e) => card.BackColor = CardHover;
            EventHandler leave = (s, e) => { if (!card.ClientRectangle.Contains(card.PointToClient(MousePosition))) card.BackColor = Card; };
            card.MouseEnter += enter; card.MouseLeave += leave;
            foreach (Control c in card.Controls) { c.MouseEnter += enter; c.MouseLeave += leave; }
            card.Paint += (s, e) =>
            {
                using (var pen = new Pen(Color.FromArgb(65, 65, 65)))
                    e.Graphics.DrawRectangle(pen, 0, 0, card.Width - 1, card.Height - 1);
                using (var brush = new LinearGradientBrush(new Rectangle(0, 0, card.Width, 3), AccentA, AccentB, 0f))
                    e.Graphics.FillRectangle(brush, 0, 0, card.Width, 3);
            };
            return card;
        }

        void Pick(string mode)
        {
            Choice = mode;
            Remember = remember.Checked;
            Close();
        }

        void DragWindow(object sender, MouseEventArgs e)
        {
            if (e.Button != MouseButtons.Left) return;
            Capture = false;
            var msg = Message.Create(Handle, 0xA1, (IntPtr)2, IntPtr.Zero);
            WndProc(ref msg);
        }

        protected override CreateParams CreateParams
        {
            get { var cp = base.CreateParams; cp.ClassStyle |= 0x20000; return cp; }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            Native.RoundCorners(Handle);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            e.Graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
            if (logo != null) e.Graphics.DrawImage(logo, S(24), S(20), S(54), S(54));
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing && logo != null) logo.Dispose();
            base.Dispose(disposing);
        }
    }
}

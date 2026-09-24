/* BinusMaya demo portal — no backend, mock data only. */
(function () {
  "use strict";
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));

  const store = {
    get(k, f) { try { const v = localStorage.getItem(k); return v == null ? f : JSON.parse(v); } catch { return f; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
  };

  // Mock data
  const COURSES = [
    { code: "COMP 101", name: "Intro to Programming", progress: 72, grade: "A-" },
    { code: "MATH 201", name: "Discrete Mathematics", progress: 45, grade: "B+" },
    { code: "ENG 102", name: "Academic Writing", progress: 90, grade: "A" },
  ];
  const SCHEDULE = [
    { day: "Monday", start: "08:00", end: "10:00", course: "COMP 101", room: "A-301", mode: "On-site" },
    { day: "Monday", start: "13:00", end: "15:00", course: "MATH 201", room: "Zoom", mode: "Online" },
    { day: "Tuesday", start: "10:00", end: "12:00", course: "ENG 102", room: "B-102", mode: "On-site" },
    { day: "Wednesday", start: "08:00", end: "10:00", course: "COMP 101 Lab", room: "Lab 4", mode: "On-site" },
  ];
  const DEADLINES = [
    { title: "Forum post: Week 5 discussion", course: "COMP 101", due: "Tomorrow, 23:59", overdue: false },
    { title: "Quiz 3 — Logic", course: "MATH 201", due: "Fri, 17:00", overdue: false },
    { title: "Essay draft", course: "ENG 102", due: "2 days overdue", overdue: true },
  ];
  const THREADS = [
    { title: "Week 5: recursion examples", course: "COMP 101", replies: 12, unread: true, updated: "10 min ago" },
    { title: "Quiz 3 coverage?", course: "MATH 201", replies: 5, unread: true, updated: "1 hr ago" },
    { title: "Essay formatting guide", course: "ENG 102", replies: 3, unread: false, updated: "Yesterday" },
  ];
  const NOTIFS = [
    { id: 1, text: "MATH 201 quiz opens Friday", time: "20 min ago", read: false },
    { id: 2, text: "Forum reply in COMP 101", time: "2 hrs ago", read: false },
    { id: 3, text: "ENG 102 grade posted", time: "Yesterday", read: true },
  ];

  const state = {
    user: store.get("bm-user", null),
    theme: store.get("bm-theme", null),
    notifs: store.get("bm-notifs", NOTIFS),
    route: "dashboard",
  };

  function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  // Toasts (polite live region, dismissible)
  function toast(msg) {
    const region = $("#toast-region");
    const el = document.createElement("div");
    el.className = "toast";
    el.innerHTML = `<span></span><button type="button" aria-label="Dismiss notification">×</button>`;
    el.firstElementChild.textContent = msg;
    el.querySelector("button").addEventListener("click", () => el.remove());
    region.appendChild(el);
    setTimeout(() => el.isConnected && el.remove(), 5000);
  }

  // Theme: respect OS, persist choice, keep label in sync
  function applyTheme() {
    const prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    const theme = state.theme || (prefersDark ? "dark" : "light");
    document.documentElement.dataset.theme = theme;
    const btn = $("#theme-toggle");
    const dark = theme === "dark";
    btn.setAttribute("aria-pressed", String(dark));
    btn.setAttribute("aria-label", dark ? "Switch to light mode" : "Switch to dark mode");
    btn.textContent = dark ? "☀️" : "🌙";
  }

  // Routing with focus management + aria-current
  const VIEWS = ["login", "dashboard", "schedule", "courses", "forum"];
  function currentRoute() {
    const h = location.hash.replace("#/", "");
    if (h === "login") return "login";
    return VIEWS.includes(h) ? h : "dashboard";
  }
  function render() {
    state.route = currentRoute();
    const loggedIn = !!state.user;
    const route = !loggedIn ? "login" : state.route === "login" ? "dashboard" : state.route;
    VIEWS.forEach((v) => { $("#view-" + v).hidden = v !== route; });
    $$("[data-nav]").forEach((a) => {
      const on = ("#/" + route) === a.getAttribute("href");
      if (on) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
    });
    $("#logout-btn").hidden = !loggedIn;
    if (route === "dashboard") renderDashboard();
    if (route === "schedule") renderSchedule();
    if (route === "courses") renderCourses();
    if (route === "forum") renderForum();
    renderNotifs();
    // Move focus to heading so screen-reader + keyboard users land on new content
    const h = $("#view-" + route + " h1");
    if (h && document.activeElement !== $("#login-nim")) h.focus({ preventScroll: true });
  }

  function renderDashboard() {
    $("#student-name").textContent = state.user ? state.user.nim.slice(-4) + " student" : "Student";
    const hr = new Date().getHours();
    $("#daypart").textContent = hr < 11 ? "morning" : hr < 15 ? "afternoon" : hr < 19 ? "evening" : "night";
    const el = $("#dash-content");
    el.innerHTML =
      `<div class="card"><h2>Due soon</h2><ul>` +
      DEADLINES.map((d) => `<li><span class="due${d.overdue ? " overdue" : ""}">${esc(d.title)}</span><br><small class="muted">${esc(d.course)} · ${esc(d.due)}</small></li>`).join("") +
      `</ul></div>` +
      `<div class="card"><h2>This week</h2><p class="muted">${SCHEDULE.length} sessions · next: ${esc(SCHEDULE[0].day)} ${esc(SCHEDULE[0].start)}</p><a href="#/schedule">View full schedule</a></div>` +
      `<div class="card"><h2>Unread forum</h2><p class="muted">${THREADS.filter((t) => t.unread).length} threads need you</p><a href="#/forum">Open forum</a></div>`;
  }

  function renderSchedule() {
    const el = $("#sched-content");
    if (!SCHEDULE.length) {
      el.innerHTML = `<div class="empty"><p>No classes scheduled this week.</p><a class="btn" href="#/courses">Browse courses</a></div>`;
      return;
    }
    const today = new Date().toLocaleDateString("en-US", { weekday: "long" });
    const days = [...new Set(SCHEDULE.map((s) => s.day))];
    el.innerHTML = days.map((d) =>
      `<div class="day-group"><h2>${esc(d)} ${d === today ? '<span class="pill today">Today</span>' : ""}</h2>` +
      SCHEDULE.filter((s) => s.day === d).map((s) =>
        `<div class="class-row"><time>${esc(s.start)}–${esc(s.end)}</time><div><strong>${esc(s.course)}</strong><br><small class="muted">${esc(s.room)} · ${esc(s.mode)}</small></div></div>`
      ).join("") + `</div>`
    ).join("");
  }

  function renderCourses() {
    const el = $("#courses-content");
    if (!COURSES.length) {
      el.innerHTML = `<div class="empty"><p>You're not enrolled in any courses this period.</p></div>`;
      return;
    }
    el.innerHTML = COURSES.map((c) =>
      `<article class="card"><h2>${esc(c.code)} — ${esc(c.name)}</h2>` +
      `<div class="progress" role="progressbar" aria-valuenow="${c.progress}" aria-valuemin="0" aria-valuemax="100" aria-label="${esc(c.name)} progress"><span style="width:${c.progress}%"></span></div>` +
      `<p class="muted">${c.progress}% complete · current grade ${esc(c.grade)}</p></article>`
    ).join("");
  }

  function renderForum() {
    const el = $("#forum-content");
    const sorted = [...THREADS].sort((a, b) => Number(b.unread) - Number(a.unread));
    el.innerHTML = sorted.map((t) =>
      `<article class="card"><h2>${t.unread ? "● " : ""}${esc(t.title)}</h2>` +
      `<p class="muted">${esc(t.course)} · ${t.replies} replies · ${esc(t.updated)}${t.unread ? " · <strong>unread</strong>" : ""}</p></article>`
    ).join("");
  }

  function renderNotifs() {
    const unread = state.notifs.filter((n) => !n.read).length;
    const badge = $("#notif-badge");
    badge.hidden = unread === 0;
    badge.textContent = unread > 9 ? "9+" : String(unread);
    $("#notif-btn").setAttribute("aria-label", unread ? `Notifications, ${unread} unread` : "Notifications");
    $("#notif-list").innerHTML = state.notifs.length
      ? state.notifs.map((n) => `<li class="${n.read ? "" : "unread"}">${esc(n.text)}<time>${esc(n.time)}</time></li>`).join("")
      : `<li>No notifications. You're all caught up.</li>`;
  }

  // Login: inline errors, announced + focused, no dead submit
  function setErr(input, errEl, msg) {
    errEl.hidden = !msg;
    errEl.textContent = msg || "";
    if (msg) input.setAttribute("aria-invalid", "true"); else input.removeAttribute("aria-invalid");
  }

  function init() {
    applyTheme();
    $("#theme-toggle").addEventListener("click", () => {
      state.theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
      store.set("bm-theme", state.theme);
      applyTheme();
      toast(state.theme === "dark" ? "Dark mode on" : "Light mode on");
    });

    const navToggle = $(".nav-toggle"), nav = $("#primary-nav");
    navToggle.addEventListener("click", () => {
      const open = nav.classList.toggle("open");
      navToggle.setAttribute("aria-expanded", String(open));
    });

    const panel = $("#notif-panel"), nbtn = $("#notif-btn");
    nbtn.addEventListener("click", (e) => {
      e.stopPropagation();
      const open = panel.hidden;
      panel.hidden = !open;
      nbtn.setAttribute("aria-expanded", String(open));
      if (open) $("#notif-read-all").focus();
    });
    document.addEventListener("click", (e) => {
      if (!panel.hidden && !e.target.closest(".notif-wrap")) {
        panel.hidden = true;
        nbtn.setAttribute("aria-expanded", "false");
      }
    });
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && !panel.hidden) {
        panel.hidden = true;
        nbtn.setAttribute("aria-expanded", "false");
        nbtn.focus();
      }
    });
    $("#notif-read-all").addEventListener("click", () => {
      state.notifs = state.notifs.map((n) => ({ ...n, read: true }));
      store.set("bm-notifs", state.notifs);
      renderNotifs();
      toast("All notifications marked as read");
    });

    $("#password-toggle").addEventListener("click", (e) => {
      const pw = $("#login-password"), btn = e.currentTarget;
      const show = pw.type === "password";
      pw.type = show ? "text" : "password";
      btn.textContent = show ? "Hide" : "Show";
      btn.setAttribute("aria-pressed", String(show));
    });

    const form = $("#login-form");
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      const nim = $("#login-nim"), pw = $("#login-password");
      const nimErr = $("#nim-error"), pwErr = $("#password-error"), formErr = $("#login-error");
      formErr.hidden = true;
      let firstBad = null;
      const nimOk = /^\d{10}$/.test(nim.value.trim());
      setErr(nim, nimErr, nimOk ? "" : "Enter your 10-digit NIM.");
      if (!nimOk && !firstBad) firstBad = nim;
      const pwOk = pw.value.length >= 8;
      setErr(pw, pwErr, pwOk ? "" : "Password must be at least 8 characters.");
      if (!pwOk && !firstBad) firstBad = pw;
      if (firstBad) { firstBad.focus(); return; }
      const btn = $("#login-submit");
      btn.disabled = true;
      btn.textContent = "Logging in…";
      setTimeout(() => {
        btn.disabled = false;
        btn.textContent = "Log in";
        if (nim.value.trim() === "2502001234" && pw.value === "password123") {
          state.user = { nim: nim.value.trim() };
          store.set("bm-user", state.user);
          toast("Welcome back!");
          location.hash = "#/dashboard";
          render();
        } else {
          formErr.textContent = "NIM or password didn't match. Hint: 2502001234 / password123.";
          formErr.hidden = false;
          formErr.focus();
        }
      }, 600);
    });

    $("#logout-btn").addEventListener("click", () => {
      state.user = null;
      store.set("bm-user", null);
      location.hash = "#/login";
      render();
      toast("Logged out");
      $("#login-nim").focus();
    });

    window.addEventListener("hashchange", render);
    render();
  }

  document.readyState === "loading"
    ? document.addEventListener("DOMContentLoaded", init)
    : init();
})();

const state = {
    token: sessionStorage.getItem("evently_token"),
    user: null,
    events: [],
    bookings: [],
    categories: [],
    editingEventId: null,
    activeCategory: "",
    viewingEventId: null,
    wishlist: [],
    wishlistIds: new Set()
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const money = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 });
const dateTime = new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short" });
const colors = ["#dceca8", "#c8e5df", "#f0d7ad", "#d8d4ef", "#f1cbc5", "#cee1f2"];

const statusLabels = { UPCOMING: "Upcoming", SOLD_OUT: "Sold out", CANCELLED: "Cancelled", COMPLETED: "Completed" };
function statusBadge(status) {
    const cls = `status-${status.toLowerCase().replace("_", "-")}`;
    return `<span class="status-badge ${cls}">${statusLabels[status] || status}</span>`;
}

function escapeHtml(value = "") {
    return String(value).replace(/[&<>'"]/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[char]);
}

async function api(path, options = {}) {
    const headers = { ...(options.body ? { "Content-Type": "application/json" } : {}), ...options.headers };
    if (state.token && options.auth !== false) headers.Authorization = `Bearer ${state.token}`;
    const response = await fetch(path, { ...options, headers });
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
        if (response.status === 401 && options.auth !== false) logout(false);
        const validation = body?.validationErrors ? Object.values(body.validationErrors).join(". ") : "";
        throw new Error(validation || body?.message || `Request failed (${response.status})`);
    }
    return body;
}

function toast(message, type = "success") {
    const item = document.createElement("div");
    item.className = `toast ${type}`;
    item.textContent = message;
    $("#toastRegion").append(item);
    setTimeout(() => item.remove(), 3500);
}

function setBusy(form, busy) {
    const button = $("button[type='submit']", form);
    if (!button) return;
    if (!button.dataset.label) button.dataset.label = button.textContent;
    button.disabled = busy;
    button.textContent = busy ? "Please wait..." : button.dataset.label;
}

function debounce(fn, delay = 250) {
    let timeoutId;
    return (...args) => {
        clearTimeout(timeoutId);
        timeoutId = setTimeout(() => fn(...args), delay);
    };
}

function resetPasswordToggles(root = document) {
    $$("[data-password-toggle]", root).forEach(button => {
        const input = button.closest(".password-control")?.querySelector("input");
        if (!input) return;
        input.type = "password";
        button.setAttribute("aria-label", "Show password");
        button.setAttribute("aria-pressed", "false");
        button.title = "Show password";
    });
}

function setupPasswordToggles() {
    document.addEventListener("click", event => {
        const button = event.target.closest("[data-password-toggle]");
        if (!button) return;
        const input = button.closest(".password-control")?.querySelector("input");
        if (!input) return;
        const showPassword = input.type === "password";
        input.type = showPassword ? "text" : "password";
        const label = showPassword ? "Hide password" : "Show password";
        button.setAttribute("aria-label", label);
        button.setAttribute("aria-pressed", String(showPassword));
        button.title = label;
        input.focus({ preventScroll: true });
    });
}

function openModal(id) {
    const modal = document.getElementById(id);
    if (!modal) return;
    modal.classList.add("open");
    modal.setAttribute("aria-hidden", "false");
    $("input:not([type='hidden'])", modal)?.focus();
}

function closeModals() {
    $$(".modal.open").forEach(modal => {
        modal.classList.remove("open");
        modal.setAttribute("aria-hidden", "true");
    });
    resetPasswordToggles();
    closeSharePopover();
}

function updateSessionUi() {
    const loggedIn = Boolean(state.user);
    $("#guestActions").classList.toggle("hidden", loggedIn);
    $("#profileMenu").classList.toggle("hidden", !loggedIn);
    $("#dashboardLink").classList.toggle("hidden", !loggedIn);
    $("#dashboard").classList.toggle("hidden", !loggedIn);
    $("#profileLink").classList.toggle("hidden", !loggedIn);
    $("#profile").classList.toggle("hidden", !loggedIn);
    $("#wishlistLink").classList.toggle("hidden", !loggedIn);
    $("#wishlist").classList.toggle("hidden", !loggedIn);
    if (loggedIn) {
        $("#profileEmailField").value = state.user.email;
        $("#profileForm").elements.name.value = state.user.name;
        $("#profileForm").elements.phone.value = state.user.phone || "";
    }
    const heroBtn = document.getElementById("heroRegisterBtn");
    if (heroBtn) heroBtn.classList.toggle("hidden", loggedIn);
    const admin = state.user?.role === "ADMIN";
    $("#adminLink").classList.toggle("hidden", !admin);
    $("#admin").classList.toggle("hidden", !admin);
    if (loggedIn) {
        $("#profileName").textContent = state.user.name;
        $("#profileRole").textContent = state.user.role;
        $("#avatarButton").textContent = state.user.name.charAt(0).toUpperCase();
    }
}

async function restoreSession() {
    if (!state.token) return updateSessionUi();
    try {
        state.user = await api("/api/auth/me");
        updateSessionUi();
        await Promise.all([loadBookings(), loadWishlist()]);
    } catch {
        logout(false);
    }
}

function logout(showToast = true) {
    sessionStorage.removeItem("evently_token");
    state.token = null;
    state.user = null;
    state.bookings = [];
    state.wishlist = [];
    state.wishlistIds = new Set();
    updateSessionUi();
    renderBookings();
    renderWishlist();
    renderEvents();
    if (showToast) toast("You have been logged out");
}

async function login(email, password) {
    const result = await api("/api/auth/login", {
        method: "POST", auth: false, body: JSON.stringify({ email, password })
    });
    applySession(result);
}

function applySession(result) {
    state.token = result.token;
    state.user = result.user;
    sessionStorage.setItem("evently_token", result.token);
    updateSessionUi();
    closeModals();
    Promise.all([loadBookings(), loadWishlist()]);
    toast(`Welcome, ${state.user.name}`);
}

async function loadWishlist() {
    if (!state.user) return;
    try {
        state.wishlist = await api("/api/wishlist");
        state.wishlistIds = new Set(state.wishlist.map(item => item.event.id));
        renderWishlist();
        renderEvents();
    } catch (error) {
        toast(error.message, "error");
    }
}

function renderWishlist() {
    const grid = $("#wishlistGrid");
    if (!grid) return;
    $("#wishlistEmpty").classList.toggle("hidden", state.wishlist.length > 0);
    grid.innerHTML = state.wishlist.map(item => {
        const event = item.event;
        const date = new Date(event.eventDate);
        const bg = event.imageUrl ? `background-image:url('${escapeHtml(event.imageUrl)}')` : `--card-color:${colors[event.id % colors.length]}`;
        return `<article class="event-card" data-view="${event.id}">
            <div class="event-visual ${event.imageUrl ? "has-image" : ""}" style="${bg}">
                <span class="event-category-tag">${escapeHtml(event.category || "OTHER")}</span>
                ${statusBadge(event.status)}
            </div>
            <div class="event-body">
                <h3>${escapeHtml(event.name)}</h3>
                <div class="event-meta"><span>${escapeHtml(event.venue)}${event.city ? " · " + escapeHtml(event.city) : ""}</span><span>•</span><span>${dateTime.format(date)}</span></div>
                <div class="event-footer">
                    <div class="event-price"><strong>${event.ticketPrice === 0 ? "Free" : money.format(event.ticketPrice)}</strong><span>${event.availableSeats} seats left</span></div>
                    <button class="button button-outline" data-wishlist-remove="${event.id}">Remove</button>
                </div>
            </div>
        </article>`;
    }).join("");
}

async function toggleWishlist(eventId) {
    if (!state.user) {
        toast("Log in to save events to your wishlist", "error");
        return openModal("loginModal");
    }
    const id = Number(eventId);
    try {
        if (state.wishlistIds.has(id)) {
            await api(`/api/wishlist/${id}`, { method: "DELETE" });
            state.wishlistIds.delete(id);
            state.wishlist = state.wishlist.filter(item => item.event.id !== id);
            toast("Removed from wishlist");
        } else {
            const saved = await api(`/api/wishlist/${id}`, { method: "POST" });
            state.wishlistIds.add(id);
            state.wishlist.unshift(saved);
            toast("Saved to wishlist");
        }
        renderWishlist();
        renderEvents();
        if (state.viewingEventId === id) updateWishlistButton(id);
    } catch (error) {
        toast(error.message, "error");
    }
}

function updateWishlistButton(eventId) {
    const btn = $("#detailsWishlistButton");
    if (!btn) return;
    const saved = state.wishlistIds.has(Number(eventId));
    btn.textContent = saved ? "♥" : "♡";
    btn.classList.toggle("active", saved);
    btn.setAttribute("aria-pressed", String(saved));
}

async function loadCategories() {
    try {
        state.categories = await api("/api/events/categories", { auth: false });
        $("#categoryChips").innerHTML = `<button type="button" class="chip ${state.activeCategory === "" ? "active" : ""}" data-category="">All</button>` +
            state.categories.map(category => `<button type="button" class="chip ${state.activeCategory === category ? "active" : ""}" data-category="${category}">${escapeHtml(category.charAt(0) + category.slice(1).toLowerCase())}</button>`).join("");
        const select = $("#eventFormCategory");
        if (select) select.innerHTML = state.categories.map(category => `<option value="${category}">${escapeHtml(category.charAt(0) + category.slice(1).toLowerCase())}</option>`).join("");
    } catch (error) {
        toast(error.message, "error");
    }
}

function showEventSkeletons() {
    $("#eventsEmpty").classList.add("hidden");
    $("#eventGrid").innerHTML = Array.from({ length: 6 }).map(() => `
        <div class="skeleton-card">
            <div class="skeleton-block visual"></div>
            <div class="skeleton-body">
                <div class="skeleton-block line"></div>
                <div class="skeleton-block line short"></div>
                <div class="skeleton-block line"></div>
            </div>
        </div>`).join("");
}

async function loadEvents() {
    showEventSkeletons();
    try {
        const params = new URLSearchParams();
        const term = $("#eventSearch").value.trim();
        const minSeats = $("#availabilityFilter").value;
        const city = $("#cityFilter").value.trim();
        const dateFrom = $("#dateFilter").value;
        const minPrice = $("#minPriceFilter").value;
        const maxPrice = $("#maxPriceFilter").value;
        if (term) params.set("search", term);
        if (minSeats > 0) params.set("minSeats", minSeats);
        if (state.activeCategory) params.set("category", state.activeCategory);
        if (city) params.set("city", city);
        if (dateFrom) params.set("dateFrom", `${dateFrom}T00:00:00`);
        if (minPrice) params.set("minPrice", minPrice);
        if (maxPrice) params.set("maxPrice", maxPrice);
        params.set("size", "50"); // load up to 50 events

        const result = await api(`/api/events?${params}`, { auth: false });
        state.events = result.content; // ← extract content from PagedResponse
        state.events.sort((a, b) => new Date(a.eventDate) - new Date(b.eventDate));
        renderEvents();
        renderAdminEvents();
        renderSpotlight();
    } catch (error) {
        toast(error.message, "error");
        $("#eventGrid").innerHTML = "";
        $("#eventsEmpty").classList.remove("hidden");
    }
}

function filteredEvents() {
    return state.events;
}

function clearAllFilters() {
    $("#eventSearch").value = "";
    $("#cityFilter").value = "";
    $("#dateFilter").value = "";
    $("#minPriceFilter").value = "";
    $("#maxPriceFilter").value = "";
    $("#availabilityFilter").value = "0";
    state.activeCategory = "";
    loadCategories();
    loadEvents();
}

function renderEvents() {
    const events = filteredEvents();
    $("#eventsEmpty").classList.toggle("hidden", events.length > 0);
    if (!events.length) { $("#eventGrid").innerHTML = ""; return; }
    $("#eventGrid").innerHTML = events.map((event, index) => {
        const date = new Date(event.eventDate);
        const bookable = event.status === "UPCOMING" && event.availableSeats > 0 && date > new Date();
        const bg = event.imageUrl ? `background-image:url('${escapeHtml(event.imageUrl)}')` : `--card-color:${colors[index % colors.length]}`;
        return `<article class="event-card" data-view="${event.id}">
            <div class="event-visual ${event.imageUrl ? "has-image" : ""}" style="${bg}">
                <span class="event-category-tag">${escapeHtml(event.category || "OTHER")}</span>
                <button class="wishlist-toggle card ${state.wishlistIds.has(event.id) ? "active" : ""}" data-wishlist="${event.id}" aria-label="Save to wishlist" type="button">${state.wishlistIds.has(event.id) ? "♥" : "♡"}</button>
                ${statusBadge(event.status)}
            </div>
            <div class="event-body">
                <h3>${escapeHtml(event.name)}</h3>
                <div class="event-meta"><span>${escapeHtml(event.venue)}${event.city ? " · " + escapeHtml(event.city) : ""}</span><span>•</span><span>${dateTime.format(date)}</span></div>
                <div class="event-footer">
                    <div class="event-price"><strong>${event.ticketPrice === 0 ? "Free" : money.format(event.ticketPrice)}</strong><span>${event.availableSeats} seats left</span></div>
                    <button class="button ${bookable ? "button-dark" : "button-outline"}" data-book="${event.id}" ${bookable ? "" : "disabled"}>${bookable ? "Book now" : "Unavailable"}</button>
                </div>
            </div>
        </article>`;
    }).join("");
}

function pad(n) { return String(n).padStart(2, "0"); }
function toIcsUtc(date) {
    return `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}T${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}Z`;
}

function buildCalendarLinks(event) {
    const start = new Date(event.eventDate);
    const end = new Date(start.getTime() + 2 * 60 * 60 * 1000); // assume 2hr duration
    const title = encodeURIComponent(event.name);
    const location = encodeURIComponent([event.venue, event.city].filter(Boolean).join(", "));
    const details = encodeURIComponent(event.description || "Booked via Evently");
    const google = `https://calendar.google.com/calendar/render?action=TEMPLATE&text=${title}&dates=${toIcsUtc(start)}/${toIcsUtc(end)}&details=${details}&location=${location}`;
    const outlook = `https://outlook.live.com/calendar/0/deeplink/compose?path=/calendar/action/compose&rru=addevent&subject=${title}&startdt=${start.toISOString()}&enddt=${end.toISOString()}&body=${details}&location=${location}`;
    const ics = [
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Evently//EN", "BEGIN:VEVENT",
        `UID:${Date.now()}@evently`, `DTSTAMP:${toIcsUtc(new Date())}`,
        `DTSTART:${toIcsUtc(start)}`, `DTEND:${toIcsUtc(end)}`,
        `SUMMARY:${event.name}`, `LOCATION:${[event.venue, event.city].filter(Boolean).join(", ")}`,
        `DESCRIPTION:${(event.description || "Booked via Evently").replace(/\n/g, " ")}`,
        "END:VEVENT", "END:VCALENDAR"
    ].join("\r\n");
    return { google, outlook, ics };
}

function openTicket(bookingRef) {
    const booking = state.bookings.find(b => b.bookingRef === bookingRef);
    if (!booking) return;
    const event = state.events.find(e => e.id === booking.eventId) || {};
    $("#ticketEventName").textContent = booking.eventName;
    $("#ticketRef").textContent = booking.bookingRef;
    $("#ticketUser").textContent = booking.userName || state.user?.name || "";
    $("#ticketVenue").textContent = [event.venue, event.city].filter(Boolean).join(", ") || "—";
    $("#ticketDate").textContent = event.eventDate ? dateTime.format(new Date(event.eventDate)) : "—";
    $("#ticketSeats").textContent = booking.seatsBooked;

    const qrPayload = JSON.stringify({ ref: booking.bookingRef, event: booking.eventName, seats: booking.seatsBooked, user: booking.userName });
    const qr = qrcode(0, "M");
    qr.addData(qrPayload);
    qr.make();
    $("#ticketQr").innerHTML = qr.createSvgTag({ cellSize: 4, margin: 2 });

    if (event.eventDate) {
        const links = buildCalendarLinks(event);
        $("#ticketCalGoogle").href = links.google;
        $("#ticketCalOutlook").href = links.outlook;
        $("#ticketCalIcs").onclick = () => {
            const blob = new Blob([links.ics], { type: "text/calendar" });
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = `${booking.bookingRef}.ics`;
            a.click();
            URL.revokeObjectURL(url);
        };
    }
    openModal("ticketModal");
}

let shareEventContext = null;
function openSharePopover(anchor, event) {
    shareEventContext = event;
    const popover = $("#sharePopover");
    popover.classList.remove("hidden");
    const rect = anchor.getBoundingClientRect();
    popover.style.top = `${window.scrollY + rect.bottom + 8}px`;
    popover.style.left = `${window.scrollX + rect.left}px`;
}
function closeSharePopover() {
    $("#sharePopover").classList.add("hidden");
    shareEventContext = null;
}

function openEventDetails(eventId) {
    const event = state.events.find(item => item.id === Number(eventId));
    if (!event) return;
    state.viewingEventId = event.id;
    const date = new Date(event.eventDate);
    const bookable = event.status === "UPCOMING" && event.availableSeats > 0 && date > new Date();
    $("#detailsImage").style.background = event.imageUrl
        ? `url('${event.imageUrl}') center/cover no-repeat`
        : `var(--card-color,#e8efca)`;
    $("#detailsBadges").innerHTML = statusBadge(event.status);
    $("#detailsCategory").textContent = event.category || "OTHER";
    $("#detailsName").textContent = event.name;
    $("#detailsMeta").textContent = `${event.venue}${event.city ? " · " + event.city : ""}`;
    $("#detailsDescription").textContent = event.description || "No description provided for this event yet.";
    $("#detailsVenue").textContent = event.venue;
    $("#detailsDate").textContent = dateTime.format(date);
    $("#detailsSeats").textContent = `${event.availableSeats} / ${event.totalSeats}`;
    $("#detailsPrice").textContent = event.ticketPrice === 0 ? "Free" : money.format(event.ticketPrice);
    $("#detailsPolicy").textContent = event.cancellationPolicy || "";
    const bookBtn = $("#detailsBookButton");
    bookBtn.disabled = !bookable;
    bookBtn.textContent = bookable ? "Book now" : "Unavailable";
    updateWishlistButton(event.id);
    loadReviews(event.id);
    openModal("eventDetailsModal");
}

async function loadReviews(eventId) {
    const list = $("#reviewsList");
    list.innerHTML = `<p class="empty-hint">Loading reviews…</p>`;
    try {
        const summary = await api(`/api/events/${eventId}/reviews`, { auth: false });
        $("#reviewsSummary").textContent = summary.totalReviews
            ? `★ ${summary.averageRating.toFixed(1)} · ${summary.totalReviews} review${summary.totalReviews === 1 ? "" : "s"}`
            : "No reviews yet";
        list.innerHTML = summary.reviews.length
            ? summary.reviews.map(r => `<div class="review-item">
                <div class="review-item-head"><strong>${escapeHtml(r.userName)}</strong><span>${"★".repeat(r.rating)}${"☆".repeat(5 - r.rating)}</span></div>
                ${r.comment ? `<p>${escapeHtml(r.comment)}</p>` : ""}
                <small>${dateTime.format(new Date(r.createdAt))}</small>
            </div>`).join("")
            : `<p class="empty-hint">Be the first to review this event.</p>`;

        const event = state.events.find(e => e.id === eventId);
        const hasConfirmedBooking = state.bookings.some(b => b.eventId === eventId && b.status === "CONFIRMED");
        const alreadyReviewed = summary.reviews.some(r => r.userId === state.user?.id);
        const eligible = state.user && event?.status === "COMPLETED" && hasConfirmedBooking && !alreadyReviewed;
        $("#reviewForm").classList.toggle("hidden", !eligible);
        $("#reviewForm").dataset.eventId = eventId;
    } catch (error) {
        list.innerHTML = `<p class="empty-hint">Could not load reviews.</p>`;
    }
}

function renderSpotlight() {
    const event = state.events.find(item => item.status === "UPCOMING" && item.availableSeats > 0);
    if (!event) return;
    const hasImage = Boolean(event.imageUrl);
    const styleAttr = hasImage ? ` style="background-image:url('${escapeHtml(event.imageUrl)}')"` : "";
    $("#spotlightCard").innerHTML = `<div class="spotlight-date ${hasImage ? "has-image" : ""}"${styleAttr}><span>${new Date(event.eventDate).getDate()}</span><strong>${new Date(event.eventDate).toLocaleString("en", { month: "long" }).toUpperCase()}</strong></div><div><span class="tag">Featured event</span><h2>${escapeHtml(event.name)}</h2><p>${escapeHtml(event.venue)} · ${dateTime.format(new Date(event.eventDate))}</p></div>`;
}

function beginBooking(eventId) {
    if (!state.user) {
        toast("Log in before booking your seat", "error");
        return openModal("loginModal");
    }
    const event = state.events.find(item => item.id === Number(eventId));
    if (!event) return;
    const form = $("#bookingForm");
    form.elements.eventId.value = event.id;
    form.elements.seatsRequired.value = 1;
    form.elements.seatsRequired.max = Math.min(10, event.availableSeats);
    form.dataset.price = event.ticketPrice;
    $("#bookingEventName").textContent = event.name;
    $("#bookingEventMeta").textContent = `${event.venue} · ${dateTime.format(new Date(event.eventDate))}`;
    updateBookingTotal();
    openModal("bookingModal");
}

function updateBookingTotal() {
    const form = $("#bookingForm");
    $("#bookingTotal").textContent = money.format(Number(form.dataset.price || 0) * Number(form.elements.seatsRequired.value || 0));
}

async function loadBookings() {
    if (!state.user) return;
    try {
        state.bookings = await api("/api/bookings");
        renderBookings();
    } catch (error) {
        toast(error.message, "error");
    }
}

function renderBookings() {
    const confirmed = state.bookings.filter(b => b.status === "CONFIRMED");

    $("#confirmedCount").textContent = confirmed.length;
    $("#seatCount").textContent = confirmed.reduce((t, b) => t + b.seatsBooked, 0);
    $("#spentAmount").textContent = money.format(confirmed.reduce((t, b) => t + b.totalAmount, 0));

    const markup = !state.bookings.length
        ? `<div class="empty-state"><strong>No bookings yet</strong><span>Your confirmed experiences will appear here.</span></div>`
        : state.bookings.map(booking => {
            const isPending  = booking.status === "PENDING";
            const isCancelled = booking.status === "CANCELLED";
            const isConfirmed = booking.status === "CONFIRMED";

            // Show expiry countdown for pending bookings
            const expiryNote = isPending && booking.expiresAt
                ? `<small class="expiry-note">Expires ${dateTime.format(new Date(booking.expiresAt))}</small>`
                : "";

            const actions = isConfirmed
                ? `<button class="button button-outline compact" data-ticket="${escapeHtml(booking.bookingRef)}">View ticket</button>
                   <button class="button button-danger compact" data-cancel-booking="${escapeHtml(booking.bookingRef)}">Cancel</button>`
                : isPending
                ? `<button class="button button-primary compact" data-confirm-booking="${escapeHtml(booking.bookingRef)}">Confirm</button>
                   <button class="button button-danger compact" data-cancel-booking="${escapeHtml(booking.bookingRef)}">Release</button>`
                : "";

            return `<article class="booking-item">
                <div>
                    <span class="status ${isCancelled ? "cancelled" : isPending ? "pending" : ""}">${booking.status}</span>
                    <h3>${escapeHtml(booking.eventName)}</h3>
                    <p>${booking.seatsBooked} seat${booking.seatsBooked === 1 ? "" : "s"} · Ref ${escapeHtml(booking.bookingRef)}</p>
                    ${expiryNote}
                </div>
                <div>
                    <strong>${money.format(booking.totalAmount)}</strong>
                    <small>${dateTime.format(new Date(booking.bookedAt))}</small>
                </div>
                ${actions}
            </article>`;
        }).join("");

    $("#bookingList").innerHTML = markup;
    const profileList = $("#profileBookingList");
    if (profileList) profileList.innerHTML = markup;
    renderAdminStats();
}

function renderAdminStats() {
    if (state.user?.role !== "ADMIN") return;
    const confirmed = state.bookings.filter(b => b.status === "CONFIRMED");
    const pending = state.bookings.filter(b => b.status === "PENDING");
    const cancelled = state.bookings.filter(b => b.status === "CANCELLED");
    $("#statTotalEvents").textContent = state.events.length;
    $("#statTotalBookings").textContent = state.bookings.length;
    $("#statRevenue").textContent = money.format(confirmed.reduce((t, b) => t + b.totalAmount, 0));
    $("#statConfirmed").textContent = confirmed.length;
    $("#statPending").textContent = pending.length;
    $("#statCancelled").textContent = cancelled.length;

    if (!state.events.length) {
        $("#seatsSoldTable").innerHTML = `<p class="empty-hint">No events yet.</p>`;
        return;
    }
    $("#seatsSoldTable").innerHTML = `<table class="seats-table"><thead><tr><th>Event</th><th>Sold</th><th>Total</th><th>% full</th></tr></thead><tbody>${
        state.events.map(event => {
            const sold = event.totalSeats - event.availableSeats;
            const pct = event.totalSeats ? Math.round((sold / event.totalSeats) * 100) : 0;
            return `<tr><td>${escapeHtml(event.name)}</td><td>${sold}</td><td>${event.totalSeats}</td><td>${pct}%</td></tr>`;
        }).join("")
    }</tbody></table>`;
}

function renderAdminEvents() {
    if (state.user?.role !== "ADMIN") return;
    $("#adminEventCount").textContent = `${state.events.length} event${state.events.length === 1 ? "" : "s"}`;
    $("#adminEventList").innerHTML = state.events.map(event => `<article class="admin-event"><div><h4>${escapeHtml(event.name)} ${statusBadge(event.status)}</h4><p>${escapeHtml(event.venue)} · ${dateTime.format(new Date(event.eventDate))} · ${event.availableSeats}/${event.totalSeats} available</p></div><div class="admin-event-actions">${event.status === "UPCOMING" ? `<button class="button button-outline compact" data-edit-event="${event.id}">Edit</button><button class="button button-danger compact" data-cancel-event="${event.id}">Cancel</button>` : ""}</div></article>`).join("");
    renderAdminStats();
}

function editEvent(id) {
    const event = state.events.find(item => item.id === Number(id));
    if (!event) return;
    state.editingEventId = event.id;
    const form = $("#eventForm");
    form.elements.name.value = event.name;
    form.elements.venue.value = event.venue;
    form.elements.city.value = event.city || "";
    form.elements.eventDate.value = event.eventDate.slice(0, 16);
    form.elements.ticketPrice.value = event.ticketPrice;
    form.elements.totalSeats.value = event.totalSeats;
    form.elements.totalSeats.disabled = true;
    form.elements.category.value = event.category || "OTHER";
    form.elements.imageUrl.value = event.imageUrl || "";
    form.elements.description.value = event.description || "";
    $("#eventFormTitle").textContent = "Edit event";
    $("#eventSubmit").textContent = "Save changes";
    $("#eventSubmit").dataset.label = "Save changes";
    $("#cancelEdit").classList.remove("hidden");
    form.scrollIntoView({ behavior: "smooth", block: "center" });
}

function resetEventForm() {
    state.editingEventId = null;
    const form = $("#eventForm");
    form.reset();
    form.elements.totalSeats.disabled = false;
    form.elements.totalSeats.value = 100;
    form.elements.ticketPrice.value = 0;
    $("#eventFormTitle").textContent = "Create an event";
    $("#eventSubmit").textContent = "Publish event";
    $("#eventSubmit").dataset.label = "Publish event";
    $("#cancelEdit").classList.add("hidden");
}

document.addEventListener("click", async event => {
    const open = event.target.closest("[data-open]");
    const switchTo = event.target.closest("[data-switch]");
    const book = event.target.closest("[data-book]");
    const view = event.target.closest("[data-view]");
    const wishlistToggle = event.target.closest("[data-wishlist]");
    const wishlistRemove = event.target.closest("[data-wishlist-remove]");
    const cancelBooking = event.target.closest("[data-cancel-booking]");
    const ticketBtn = event.target.closest("[data-ticket]");
    const edit = event.target.closest("[data-edit-event]");
    const cancelEvent = event.target.closest("[data-cancel-event]");
    const chip = event.target.closest("[data-category]");
    const shareAction = event.target.closest("[data-share-action]");
    if (open) openModal(open.dataset.open);
    if (event.target.closest("[data-close]")) closeModals();
    if (switchTo) { closeModals(); openModal(switchTo.dataset.switch); }
    if (book) { beginBooking(book.dataset.book); return; }
    if (wishlistToggle) { toggleWishlist(wishlistToggle.dataset.wishlist); return; }
    if (wishlistRemove) { toggleWishlist(wishlistRemove.dataset.wishlistRemove); return; }
    if (view) { openEventDetails(view.dataset.view); return; }
    if (ticketBtn) { openTicket(ticketBtn.dataset.ticket); return; }
    if (edit) editEvent(edit.dataset.editEvent);
    if (chip) {
        state.activeCategory = chip.dataset.category;
        $$("#categoryChips .chip").forEach(c => c.classList.toggle("active", c === chip));
        loadEvents();
    }
    if (event.target.id === "detailsWishlistButton" || event.target.closest("#detailsWishlistButton")) {
        if (state.viewingEventId) toggleWishlist(state.viewingEventId);
        return;
    }
    if (event.target.id === "detailsShareButton") {
        const eventData = state.events.find(e => e.id === state.viewingEventId);
        if (eventData) openSharePopover(event.target, eventData);
        return;
    }
    if (shareAction && shareEventContext) {
        const url = `${location.origin}${location.pathname}#events?event=${shareEventContext.id}`;
        if (shareAction.dataset.shareAction === "copy") {
            navigator.clipboard.writeText(url).then(() => toast("Event link copied")).catch(() => toast("Could not copy link", "error"));
        } else {
            window.open(`https://wa.me/?text=${encodeURIComponent(`${shareEventContext.name} — ${url}`)}`, "_blank");
        }
        closeSharePopover();
        return;
    }
    if (!event.target.closest("#sharePopover") && event.target.id !== "detailsShareButton") closeSharePopover();
    if (cancelBooking && confirm("Cancel this booking and release its seats?")) {
        try { await api(`/api/bookings/${cancelBooking.dataset.cancelBooking}`, { method: "DELETE" }); toast("Booking cancelled"); await Promise.all([loadBookings(), loadEvents()]); } catch (error) { toast(error.message, "error"); }
    }
    const confirmBooking = event.target.closest("[data-confirm-booking]");

    if (confirmBooking) {
        try {
            await api(`/api/bookings/${confirmBooking.dataset.confirmBooking}/confirm`,
                      { method: "POST" });
            toast("Booking confirmed! Your seats are secured.");
            await Promise.all([loadBookings(), loadEvents()]);
        } catch (error) {
            toast(error.message, "error");
        }
    }
    if (cancelEvent && confirm("Cancel this event and all confirmed bookings?")) {
        try { await api(`/api/events/${cancelEvent.dataset.cancelEvent}`, { method: "DELETE" }); toast("Event cancelled"); await loadEvents(); } catch (error) { toast(error.message, "error"); }
    }
});

$("#loginForm").addEventListener("submit", async event => {
    event.preventDefault(); setBusy(event.currentTarget, true);
    try { await login(event.currentTarget.elements.email.value, event.currentTarget.elements.password.value); }
    catch (error) { toast(error.message, "error"); }
    finally { setBusy(event.currentTarget, false); }
});

$("#registerForm").addEventListener("submit", async event => {
    event.preventDefault(); const form = event.currentTarget;
    const data = Object.fromEntries(new FormData(form));
    if (data.password !== data.confirmPassword) {
        form.elements.confirmPassword.setCustomValidity("Passwords do not match");
        form.reportValidity();
        form.elements.confirmPassword.setCustomValidity("");
        form.elements.confirmPassword.focus();
        return;
    }
    delete data.confirmPassword;
    setBusy(form, true);
    try {
        await api("/api/users", { method: "POST", auth: false, body: JSON.stringify(data) });
        form.reset();
        closeModals();
        $("#otpForm").elements.email.value = data.email;
        $("#otpEmailDisplay").textContent = data.email;
        openModal("otpModal");
        toast("Check your email for a verification code");
    }
    catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

$("#otpForm").addEventListener("submit", async event => {
    event.preventDefault(); const form = event.currentTarget;
    setBusy(form, true);
    try {
        const result = await api("/api/auth/verify-otp", {
            method: "POST", auth: false,
            body: JSON.stringify({ email: form.elements.email.value, otp: form.elements.otp.value })
        });
        form.reset();
        applySession(result);
    }
    catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

$("#resendOtpBtn").addEventListener("click", async () => {
    const button = $("#resendOtpBtn");
    const email = $("#otpForm").elements.email.value;
    if (!email) return;
    button.disabled = true;
    try {
        await api("/api/auth/resend-otp", { method: "POST", auth: false, body: JSON.stringify({ email }) });
        toast("A new code is on its way");
    }
    catch (error) { toast(error.message, "error"); }
    finally { setTimeout(() => { button.disabled = false; }, 5000); }
});

$("#bookingForm").addEventListener("submit", async event => {
    event.preventDefault(); const form = event.currentTarget; setBusy(form, true);
    try {
        await api("/api/bookings", { method: "POST", body: JSON.stringify({ userId: state.user.id, eventId: Number(form.elements.eventId.value), seatsRequired: Number(form.elements.seatsRequired.value) }) });
        closeModals(); toast("Seats held for 5 minutes - confirm to secure your booking"); await Promise.all([loadBookings(), loadEvents()]);
    } catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

$("#profileForm").addEventListener("submit", async event => {
    event.preventDefault(); const form = event.currentTarget; setBusy(form, true);
    try {
        const updated = await api("/api/users/me", { method: "PUT", body: JSON.stringify({ name: form.elements.name.value, phone: form.elements.phone.value }) });
        state.user = { ...state.user, ...updated };
        updateSessionUi();
        toast("Profile updated");
    } catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

$("#passwordForm").addEventListener("submit", async event => {
    event.preventDefault(); const form = event.currentTarget;
    if (form.elements.newPassword.value !== form.elements.confirmPassword.value) {
        form.elements.confirmPassword.setCustomValidity("Passwords do not match");
        form.reportValidity();
        form.elements.confirmPassword.setCustomValidity("");
        return;
    }
    setBusy(form, true);
    try {
        await api("/api/users/me/password", { method: "PUT", body: JSON.stringify({ currentPassword: form.elements.currentPassword.value, newPassword: form.elements.newPassword.value }) });
        toast("Password updated");
        form.reset();
    } catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

$("#reviewForm").addEventListener("submit", async event => {
    event.preventDefault(); const form = event.currentTarget; setBusy(form, true);
    const eventId = form.dataset.eventId;
    try {
        await api(`/api/events/${eventId}/reviews`, { method: "POST", body: JSON.stringify({ rating: Number(form.elements.rating.value), comment: form.elements.comment.value || null }) });
        toast("Thanks for your review!");
        form.reset();
        await loadReviews(Number(eventId));
    } catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

$("#eventForm").addEventListener("submit", async event => {
    event.preventDefault(); const form = event.currentTarget; setBusy(form, true);
    const raw = Object.fromEntries(new FormData(form));
    const payload = {
        name: raw.name, venue: raw.venue, city: raw.city || null, eventDate: raw.eventDate,
        ticketPrice: Number(raw.ticketPrice), category: raw.category || null,
        imageUrl: raw.imageUrl || null, description: raw.description || null
    };
    if (!state.editingEventId) payload.totalSeats = Number(raw.totalSeats);
    try {
        await api(state.editingEventId ? `/api/events/${state.editingEventId}` : "/api/events", { method: state.editingEventId ? "PUT" : "POST", body: JSON.stringify(payload) });
        toast(state.editingEventId ? "Event updated" : "Event published"); resetEventForm(); await loadEvents();
    } catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

const debouncedLoadEvents = debounce(loadEvents);
$("#eventSearch").addEventListener("input", debouncedLoadEvents);
$("#availabilityFilter").addEventListener("change", loadEvents);
$("#cityFilter").addEventListener("input", debouncedLoadEvents);
$("#dateFilter").addEventListener("change", loadEvents);
$("#minPriceFilter").addEventListener("input", debouncedLoadEvents);
$("#maxPriceFilter").addEventListener("input", debouncedLoadEvents);
$("#clearFilters").addEventListener("click", clearAllFilters);
$("#emptyClearFilters").addEventListener("click", clearAllFilters);
$("#detailsBookButton").addEventListener("click", () => {
    if (!state.viewingEventId) return;
    closeModals();
    beginBooking(state.viewingEventId);
});
$("#bookingForm").elements.seatsRequired.addEventListener("input", updateBookingTotal);
$("#logoutButton").addEventListener("click", () => logout());
$("#refreshBookings").addEventListener("click", loadBookings);
$("#cancelEdit").addEventListener("click", resetEventForm);
document.addEventListener("keydown", event => { if (event.key === "Escape") closeModals(); });

function applyTheme(theme) {
    if (theme === "dark") {
        document.documentElement.setAttribute("data-theme", "dark");
    } else {
        document.documentElement.removeAttribute("data-theme");
    }
    const btn = $("#themeToggle");
    btn.textContent = theme === "dark" ? "☀️" : "🌙";
    btn.setAttribute("aria-pressed", String(theme === "dark"));
    localStorage.setItem("evently_theme", theme);
}

function initTheme() {
    const saved = localStorage.getItem("evently_theme");
    const preferred = saved || "light";
    applyTheme(preferred);
}

$("#themeToggle").addEventListener("click", () => {
    const isDark = document.documentElement.getAttribute("data-theme") === "dark";
    applyTheme(isDark ? "light" : "dark");
});

function initGoogleSignIn() {
    if (!window.google?.accounts?.id) return;
    const clientId = window.GOOGLE_CLIENT_ID || "";
    if (!clientId || clientId.includes("YOUR_GOOGLE_CLIENT_ID")) return; // not configured yet
    google.accounts.id.initialize({ client_id: clientId, callback: handleGoogleCredential });
    ["googleSignInLogin", "googleSignInRegister"].forEach(id => {
        const el = document.getElementById(id);
        if (el) google.accounts.id.renderButton(el, { theme: "outline", size: "large", width: 320 });
    });
}

async function handleGoogleCredential(response) {
    try {
        const result = await api("/api/auth/google", {
            method: "POST", auth: false, body: JSON.stringify({ credential: response.credential })
        });
        state.token = result.token;
        state.user = result.user;
        sessionStorage.setItem("evently_token", result.token);
        updateSessionUi();
        closeModals();
        await Promise.all([loadBookings(), loadWishlist()]);
        toast(`Welcome, ${state.user.name}`);
    } catch (error) {
        toast(error.message, "error");
    }
}

window.addEventListener("load", initGoogleSignIn);

setupPasswordToggles();
initTheme();
updateSessionUi();
loadCategories();
loadEvents();
restoreSession().then(renderAdminEvents);
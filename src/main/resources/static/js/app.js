const state = {
    token: sessionStorage.getItem("evently_token"),
    user: null,
    events: [],
    bookings: [],
    editingEventId: null
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const money = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 });
const dateTime = new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short" });
const colors = ["#dceca8", "#c8e5df", "#f0d7ad", "#d8d4ef", "#f1cbc5", "#cee1f2"];

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
}

function updateSessionUi() {
    const loggedIn = Boolean(state.user);
    $("#guestActions").classList.toggle("hidden", loggedIn);
    $("#profileMenu").classList.toggle("hidden", !loggedIn);
    $("#dashboardLink").classList.toggle("hidden", !loggedIn);
    $("#dashboard").classList.toggle("hidden", !loggedIn);
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
        await loadBookings();
    } catch {
        logout(false);
    }
}

function logout(showToast = true) {
    sessionStorage.removeItem("evently_token");
    state.token = null;
    state.user = null;
    state.bookings = [];
    updateSessionUi();
    renderBookings();
    if (showToast) toast("You have been logged out");
}

async function login(email, password) {
    const result = await api("/api/auth/login", {
        method: "POST", auth: false, body: JSON.stringify({ email, password })
    });
    state.token = result.token;
    state.user = result.user;
    sessionStorage.setItem("evently_token", result.token);
    updateSessionUi();
    closeModals();
    await loadBookings();
    toast(`Welcome, ${state.user.name}`);
}

async function loadEvents() {
    try {
        const params = new URLSearchParams();
        const term = $("#eventSearch").value.trim();
        const minSeats = $("#availabilityFilter").value;
        if (term) params.set("search", term);
        if (minSeats > 0) params.set("minSeats", minSeats);
        params.set("size", "50"); // load up to 50 events

        const result = await api(`/api/events?${params}`, { auth: false });
        state.events = result.content; // ← extract content from PagedResponse
        state.events.sort((a, b) => new Date(a.eventDate) - new Date(b.eventDate));
        renderEvents();
        renderAdminEvents();
        renderSpotlight();
    } catch (error) {
        toast(error.message, "error");
    }
}

function filteredEvents() {
    return state.events;
}

function renderEvents() {
    const events = filteredEvents();
    $("#eventsEmpty").classList.toggle("hidden", events.length > 0);
    $("#eventGrid").innerHTML = events.map((event, index) => {
        const date = new Date(event.eventDate);
        const bookable = event.status === "UPCOMING" && event.availableSeats > 0 && date > new Date();
        return `<article class="event-card">
            <div class="event-visual" style="--card-color:${colors[index % colors.length]}">
                <div class="date-chip"><strong>${date.getDate()}</strong><span>${date.toLocaleString("en", { month: "short" }).toUpperCase()}</span></div>
                <span class="availability">${escapeHtml(event.status.replace("_", " "))} · ${event.availableSeats} seats</span>
            </div>
            <div class="event-body">
                <h3>${escapeHtml(event.name)}</h3>
                <div class="event-meta"><span>${escapeHtml(event.venue)}</span><span>•</span><span>${dateTime.format(date)}</span></div>
                <div class="event-footer">
                    <div class="event-price"><strong>${event.ticketPrice === 0 ? "Free" : money.format(event.ticketPrice)}</strong><span>per person</span></div>
                    <button class="button ${bookable ? "button-dark" : "button-outline"}" data-book="${event.id}" ${bookable ? "" : "disabled"}>${bookable ? "Book now" : "Unavailable"}</button>
                </div>
            </div>
        </article>`;
    }).join("");
}

function renderSpotlight() {
    const event = state.events.find(item => item.status === "UPCOMING" && item.availableSeats > 0);
    if (!event) return;
    $("#spotlightCard").innerHTML = `<div class="spotlight-date"><span>${new Date(event.eventDate).getDate()}</span><strong>${new Date(event.eventDate).toLocaleString("en", { month: "long" }).toUpperCase()}</strong></div><div><span class="tag">Featured event</span><h2>${escapeHtml(event.name)}</h2><p>${escapeHtml(event.venue)} · ${dateTime.format(new Date(event.eventDate))}</p></div>`;
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
    const pending = state.bookings.filter(b => b.status === "PENDING");

    $("#confirmedCount").textContent = confirmed.length;
    $("#seatCount").textContent = confirmed.reduce((t, b) => t + b.seatsBooked, 0);
    $("#spentAmount").textContent = money.format(confirmed.reduce((t, b) => t + b.totalAmount, 0));

    if (!state.bookings.length) {
        $("#bookingList").innerHTML = `<div class="empty-state"><strong>No bookings yet</strong><span>Your confirmed experiences will appear here.</span></div>`;
        return;
    }

    $("#bookingList").innerHTML = state.bookings.map(booking => {
        const isPending  = booking.status === "PENDING";
        const isCancelled = booking.status === "CANCELLED";
        const isConfirmed = booking.status === "CONFIRMED";

        // Show expiry countdown for pending bookings
        const expiryNote = isPending && booking.expiresAt
            ? `<small class="expiry-note">Expires ${dateTime.format(new Date(booking.expiresAt))}</small>`
            : "";

        const actions = isConfirmed
            ? `<button class="button button-danger compact" data-cancel-booking="${escapeHtml(booking.bookingRef)}">Cancel</button>`
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
}

function renderAdminEvents() {
    if (state.user?.role !== "ADMIN") return;
    $("#adminEventCount").textContent = `${state.events.length} event${state.events.length === 1 ? "" : "s"}`;
    $("#adminEventList").innerHTML = state.events.map(event => `<article class="admin-event"><div><h4>${escapeHtml(event.name)} <span class="status ${event.status === "CANCELLED" ? "cancelled" : ""}">${event.status}</span></h4><p>${escapeHtml(event.venue)} · ${dateTime.format(new Date(event.eventDate))} · ${event.availableSeats}/${event.totalSeats} available</p></div><div class="admin-event-actions">${event.status === "UPCOMING" ? `<button class="button button-outline compact" data-edit-event="${event.id}">Edit</button><button class="button button-danger compact" data-cancel-event="${event.id}">Cancel</button>` : ""}</div></article>`).join("");
}

function editEvent(id) {
    const event = state.events.find(item => item.id === Number(id));
    if (!event) return;
    state.editingEventId = event.id;
    const form = $("#eventForm");
    form.elements.name.value = event.name;
    form.elements.venue.value = event.venue;
    form.elements.eventDate.value = event.eventDate.slice(0, 16);
    form.elements.ticketPrice.value = event.ticketPrice;
    form.elements.totalSeats.value = event.totalSeats;
    form.elements.totalSeats.disabled = true;
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
    const cancelBooking = event.target.closest("[data-cancel-booking]");
    const edit = event.target.closest("[data-edit-event]");
    const cancelEvent = event.target.closest("[data-cancel-event]");
    if (open) openModal(open.dataset.open);
    if (event.target.closest("[data-close]")) closeModals();
    if (switchTo) { closeModals(); openModal(switchTo.dataset.switch); }
    if (book) beginBooking(book.dataset.book);
    if (edit) editEvent(edit.dataset.editEvent);
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
    event.preventDefault(); const form = event.currentTarget; setBusy(form, true);
    const data = Object.fromEntries(new FormData(form));
    try { await api("/api/users", { method: "POST", auth: false, body: JSON.stringify(data) }); await login(data.email, data.password); form.reset(); }
    catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

$("#bookingForm").addEventListener("submit", async event => {
    event.preventDefault(); const form = event.currentTarget; setBusy(form, true);
    try {
        await api("/api/bookings", { method: "POST", body: JSON.stringify({ userId: state.user.id, eventId: Number(form.elements.eventId.value), seatsRequired: Number(form.elements.seatsRequired.value) }) });
        closeModals(); toast("Seats held for 5 minutes - confirm to secure your booking"); await Promise.all([loadBookings(), loadEvents()]);
    } catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

$("#eventForm").addEventListener("submit", async event => {
    event.preventDefault(); const form = event.currentTarget; setBusy(form, true);
    const raw = Object.fromEntries(new FormData(form));
    const payload = { name: raw.name, venue: raw.venue, eventDate: raw.eventDate, ticketPrice: Number(raw.ticketPrice) };
    if (!state.editingEventId) payload.totalSeats = Number(raw.totalSeats);
    try {
        await api(state.editingEventId ? `/api/events/${state.editingEventId}` : "/api/events", { method: state.editingEventId ? "PUT" : "POST", body: JSON.stringify(payload) });
        toast(state.editingEventId ? "Event updated" : "Event published"); resetEventForm(); await loadEvents();
    } catch (error) { toast(error.message, "error"); }
    finally { setBusy(form, false); }
});

$("#eventSearch").addEventListener("input", loadEvents);
$("#availabilityFilter").addEventListener("change", loadEvents);
$("#bookingForm").elements.seatsRequired.addEventListener("input", updateBookingTotal);
$("#logoutButton").addEventListener("click", () => logout());
$("#refreshBookings").addEventListener("click", loadBookings);
$("#cancelEdit").addEventListener("click", resetEventForm);
document.addEventListener("keydown", event => { if (event.key === "Escape") closeModals(); });

updateSessionUi();
loadEvents();
restoreSession().then(renderAdminEvents);
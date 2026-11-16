package shift

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"sync"
	"time"

	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/space"
)

// Reading a photo takes about a minute: longer than CloudFront waits for an
// answer (60 seconds) and than the server lets a response take (30). So the
// apps start a read, which answers at once with its id, and ask after it
// every few seconds until it's done:
//
//	POST /api/shifts/reads      202 {"id": "…"}, or the error POST /api/shifts/read gives
//	GET  /api/shifts/reads/{id} 202 {"status": "reading"}, then exactly what
//	                            POST /api/shifts/read answers (the month, or its error)
//
// Reads live in this process's memory, like the photo while it's read: a
// finished one is forgotten after keepRead, and a restart forgets them all
// (404, so the app asks for the photo again).

// keepRead is how long a finished read waits to be asked after.
const keepRead = 10 * time.Minute

// maxReadsPerSpace is how many reads one space can have going at once.
const maxReadsPerSpace = 3

type reads struct {
	mu   sync.Mutex
	byID map[string]*pendingRead
}

type pendingRead struct {
	spaceID  int64
	done     bool
	view     any
	err      error
	finished time.Time
}

// prune forgets finished reads nobody asked after. Call with mu held.
func (rs *reads) prune(now time.Time) {
	for id, p := range rs.byID {
		if p.done && now.Sub(p.finished) > keepRead {
			delete(rs.byID, id)
		}
	}
}

func (h *Handler) startRead(w http.ResponseWriter, r *http.Request) error {
	run, err := h.prepareRead(w, r)
	if err != nil {
		return err
	}
	spaceID := space.SpaceFrom(r)
	var raw [16]byte
	rand.Read(raw[:])
	id := base64.RawURLEncoding.EncodeToString(raw[:])

	h.reads.mu.Lock()
	h.reads.prune(h.now())
	going := 0
	for _, p := range h.reads.byID {
		if p.spaceID == spaceID && !p.done {
			going++
		}
	}
	if going >= maxReadsPerSpace {
		h.reads.mu.Unlock()
		return httpx.Errorf(http.StatusTooManyRequests, "a photo is already being read; wait for it to finish")
	}
	if h.reads.byID == nil {
		h.reads.byID = map[string]*pendingRead{}
	}
	p := &pendingRead{spaceID: spaceID}
	h.reads.byID[id] = p
	h.reads.mu.Unlock()

	// The read outlives this request, but keeps its values (for logging).
	ctx, cancel := context.WithTimeout(context.WithoutCancel(r.Context()), readTimeout)
	go func() {
		defer cancel()
		view, err := run(ctx)
		h.reads.mu.Lock()
		defer h.reads.mu.Unlock()
		p.done, p.view, p.err, p.finished = true, view, err, h.now()
	}()

	httpx.WriteJSON(w, http.StatusAccepted, map[string]string{"id": id})
	return nil
}

func (h *Handler) readStatus(w http.ResponseWriter, r *http.Request) error {
	h.reads.mu.Lock()
	h.reads.prune(h.now())
	p, ok := h.reads.byID[r.PathValue("id")]
	if !ok || p.spaceID != space.SpaceFrom(r) {
		h.reads.mu.Unlock()
		return httpx.Errorf(http.StatusNotFound, "that photo isn't being read any more; send it again")
	}
	done, view, err := p.done, p.view, p.err
	h.reads.mu.Unlock()

	if !done {
		httpx.WriteJSON(w, http.StatusAccepted, map[string]string{"status": "reading"})
		return nil
	}
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, view)
	return nil
}

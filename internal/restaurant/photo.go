package restaurant

// Photos of a visit (migration 030). A photo is kept exactly as it was
// taken: the apps send the original file, never a smaller copy, along with a
// thumbnail of their own making for the photo strips and list cards. The
// original is stored in parts of partSize so neither an upload nor a
// download ever holds a whole photo in the server's memory.

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"strconv"
	"time"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/db"
	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/space"
)

const (
	// MaxPhoto is the largest photo accepted: a phone's full-size picture is
	// 3 to 15 MB, a 200-megapixel one up to about 30.
	MaxPhoto = 40 << 20
	maxThumb = 1 << 20
	partSize = 1 << 20
	// photoCache: a photo never changes (a new one gets a new id).
	photoCache = "private, max-age=31536000, immutable"
)

// photoTypes are the kinds of picture kept: what browsers and phones show.
var photoTypes = map[string]bool{"image/jpeg": true, "image/png": true, "image/webp": true, "image/gif": true, "image/avif": true}

// Photo is a picture from a visit. URL is the original, as it was taken;
// ThumbURL a small copy for strips and cards.
type Photo struct {
	ID       int64  `json:"id"`
	URL      string `json:"url"`
	ThumbURL string `json:"thumb_url"`
	Width    int    `json:"width"`
	Height   int    `json:"height"`
	AddedBy  string `json:"added_by"`
}

func photoURL(id int64) string { return "/api/photos/" + strconv.FormatInt(id, 10) }
func thumbURL(id int64) string { return photoURL(id) + "/thumb" }

// photosOf are a restaurant's photos by visit, oldest first within each.
func (s *Store) photosOf(ctx context.Context, id int64) (map[int64][]Photo, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT p.id, p.visit_id, p.width, p.height, coalesce(m.display_name, ''), coalesce(u.email, '')
		FROM restaurant_photos p LEFT JOIN users u ON u.id = p.added_by LEFT JOIN space_members m ON m.user_id = p.added_by
		WHERE p.restaurant_id = $1 ORDER BY p.id`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64][]Photo{}
	for rows.Next() {
		var p Photo
		var visit int64
		var name, email string
		if err := rows.Scan(&p.ID, &visit, &p.Width, &p.Height, &name, &email); err != nil {
			return nil, err
		}
		if email != "" {
			p.AddedBy = space.DisplayName(name, email)
		}
		p.URL, p.ThumbURL = photoURL(p.ID), thumbURL(p.ID)
		out[visit] = append(out[visit], p)
	}
	return out, rows.Err()
}

// newPhoto is a photo arriving: what's known before its bytes.
type newPhoto struct {
	width, height int
	thumb         []byte
	thumbType     string
}

// AddPhoto saves a photo of one of the space's restaurant's visits, reading
// the original from body (whose first bytes, already read, are head) a part
// at a time. db.ErrNotFound for a visit not there; an *httpx.Error for a
// photo too big. It returns the photo's id.
func (s *Store) AddPhoto(ctx context.Context, spaceID, by, id, visitID int64, contentType string, p newPhoto, body io.Reader) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	var photoID int64
	err = tx.QueryRowContext(ctx, `
		INSERT INTO restaurant_photos (restaurant_id, visit_id, content_type, width, height, thumb, thumb_type, added_by)
		SELECT r.id, v.id, $4, $5, $6, $7, $8, $9
		FROM restaurant_visits v JOIN restaurants r ON r.id = v.restaurant_id
		WHERE v.id = $3 AND r.id = $1 AND r.space_id = $2
		RETURNING id`, id, spaceID, visitID, contentType, p.width, p.height, p.thumb, p.thumbType, by).Scan(&photoID)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, db.ErrNotFound
	} else if err != nil {
		return 0, err
	}
	buf := make([]byte, partSize)
	var size int64
	for seq := 0; ; seq++ {
		n, rerr := io.ReadFull(body, buf)
		if n > 0 {
			size += int64(n)
			if size > MaxPhoto {
				return 0, errTooBig
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO restaurant_photo_parts (photo_id, seq, data) VALUES ($1, $2, $3)`, photoID, seq, buf[:n]); err != nil {
				return 0, err
			}
		}
		if errors.Is(rerr, io.EOF) || errors.Is(rerr, io.ErrUnexpectedEOF) {
			break
		}
		if rerr != nil {
			return 0, rerr
		}
	}
	if size == 0 {
		return 0, httpx.Errorf(http.StatusBadRequest, "the photo is empty")
	}
	if _, err := tx.ExecContext(ctx, `UPDATE restaurant_photos SET size = $2 WHERE id = $1`, photoID, size); err != nil {
		return 0, err
	}
	return photoID, tx.Commit()
}

var errTooBig = httpx.Errorf(http.StatusRequestEntityTooLarge, "a photo must be at most %d MB", MaxPhoto>>20)

// DeletePhoto removes one of the space's restaurant's photos.
func (s *Store) DeletePhoto(ctx context.Context, spaceID, id, photoID int64) error {
	res, err := s.db.ExecContext(ctx, `
		DELETE FROM restaurant_photos p USING restaurants r
		WHERE p.id = $3 AND p.restaurant_id = r.id AND r.id = $1 AND r.space_id = $2`, id, spaceID, photoID)
	return oneRow(res, err)
}

// SetCover makes one of the restaurant's photos its cover.
func (s *Store) SetCover(ctx context.Context, spaceID, id, photoID int64) error {
	res, err := s.db.ExecContext(ctx, `
		UPDATE restaurants r SET cover_photo_id = p.id
		FROM restaurant_photos p
		WHERE p.id = $3 AND p.restaurant_id = r.id AND r.id = $1 AND r.space_id = $2`, id, spaceID, photoID)
	return oneRow(res, err)
}

// photoInfo is what's served about a photo before its bytes.
type photoInfo struct {
	contentType, thumbType string
	size                   int64
	thumb                  []byte
}

// photo finds one of the space's photos; withThumb also reads its thumbnail.
func (s *Store) photo(ctx context.Context, spaceID, photoID int64, withThumb bool) (photoInfo, error) {
	var p photoInfo
	err := s.db.QueryRowContext(ctx, `
		SELECT p.content_type, p.thumb_type, p.size, CASE WHEN $3 THEN p.thumb ELSE ''::bytea END
		FROM restaurant_photos p JOIN restaurants r ON r.id = p.restaurant_id
		WHERE p.id = $1 AND r.space_id = $2`, photoID, spaceID, withThumb).Scan(&p.contentType, &p.thumbType, &p.size, &p.thumb)
	if errors.Is(err, sql.ErrNoRows) {
		return p, db.ErrNotFound
	}
	return p, err
}

// writeParts copies a photo's original to w a part at a time.
func (s *Store) writeParts(ctx context.Context, photoID int64, w io.Writer) error {
	rows, err := s.db.QueryContext(ctx, `SELECT data FROM restaurant_photo_parts WHERE photo_id = $1 ORDER BY seq`, photoID)
	if err != nil {
		return err
	}
	defer rows.Close()
	var part []byte
	for rows.Next() {
		if err := rows.Scan(&part); err != nil {
			return err
		}
		if _, err := w.Write(part); err != nil {
			return err
		}
	}
	return rows.Err()
}

// recentPhotoBy reports whether the user added a photo to the visit, other
// than photoID, in the last few minutes: a handful sent together is one
// change for the Us tab and the other phone, not one each.
func (s *Store) recentPhotoBy(ctx context.Context, userID, visitID, photoID int64) bool {
	var yes bool
	err := s.db.QueryRowContext(ctx, `
		SELECT EXISTS (SELECT 1 FROM restaurant_photos WHERE visit_id = $1 AND added_by = $2 AND id <> $3
			AND created_at > now() - interval '15 minutes')`, visitID, userID, photoID).Scan(&yes)
	return err == nil && yes
}

// ---------- HTTP ----------

// sniff returns what kind of picture starts with head, or "" if none kept.
func sniff(head []byte) string {
	// AVIF, which Go doesn't know: an ISO media file of brand avif or avis.
	if len(head) >= 12 && string(head[4:8]) == "ftyp" && (string(head[8:12]) == "avif" || string(head[8:12]) == "avis") {
		return "image/avif"
	}
	t := http.DetectContentType(head)
	if photoTypes[t] {
		return t
	}
	return ""
}

// addPhoto is POST /api/restaurants/{id}/visits/{visit}/photos: a multipart
// form with "thumb" (a small JPEG or WebP the app made), then "photo" (the
// original file, as taken), and optionally "width" and "height" (the
// original's pixels) before them. It answers with the restaurant.
func (h *Handler) addPhoto(w http.ResponseWriter, r *http.Request) error {
	id, ok := pathID(r, "id")
	visit, ok2 := pathID(r, "visit")
	if !ok || !ok2 {
		return errNoVisit
	}
	r.Body = http.MaxBytesReader(w, r.Body, MaxPhoto+maxThumb+64<<10)
	mr, err := r.MultipartReader()
	if err != nil {
		return httpx.Errorf(http.StatusBadRequest, "send the photo as a multipart form")
	}
	var p newPhoto
	sp, me := space.SpaceFrom(r), account.UserFrom(r).ID
	for {
		part, err := mr.NextPart()
		if errors.Is(err, io.EOF) {
			return httpx.Errorf(http.StatusBadRequest, "no photo was sent")
		}
		if err != nil {
			return badUpload(err)
		}
		switch part.FormName() {
		case "width", "height":
			b, _ := io.ReadAll(io.LimitReader(part, 16))
			n, _ := strconv.Atoi(string(bytes.TrimSpace(b)))
			if n < 0 || n > 100_000 {
				n = 0
			}
			if part.FormName() == "width" {
				p.width = n
			} else {
				p.height = n
			}
		case "thumb":
			b, err := io.ReadAll(io.LimitReader(part, maxThumb+1))
			if err != nil {
				return badUpload(err)
			}
			if len(b) > maxThumb {
				return httpx.Errorf(http.StatusRequestEntityTooLarge, "a thumbnail must be at most %d KB", maxThumb>>10)
			}
			if t := sniff(b); t == "" {
				return httpx.Errorf(http.StatusBadRequest, "the thumbnail must be a JPEG, PNG or WebP picture")
			} else {
				p.thumb, p.thumbType = b, t
			}
		case "photo":
			if p.thumb == nil {
				return httpx.Errorf(http.StatusBadRequest, "send the thumbnail before the photo")
			}
			head := make([]byte, 512)
			n, err := io.ReadFull(part, head)
			if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
				return badUpload(err)
			}
			kind := sniff(head[:n])
			if kind == "" {
				return httpx.Errorf(http.StatusUnsupportedMediaType, "that kind of photo can't be shown; send a JPEG, PNG, WebP or AVIF")
			}
			photoID, err := h.store.AddPhoto(r.Context(), sp, me, id, visit, kind, p, io.MultiReader(bytes.NewReader(head[:n]), part))
			if errors.Is(err, db.ErrNotFound) {
				return errNoVisit
			}
			if err != nil {
				return badUpload(err)
			}
			if !h.store.recentPhotoBy(r.Context(), me, visit, photoID) {
				h.log(r, "photos_added", id, nil)
			}
			return h.respond(w, r, http.StatusCreated, id)
		default:
			// Unknown fields are skipped, for apps newer than this server.
			if _, err := io.Copy(io.Discard, io.LimitReader(part, 64<<10)); err != nil {
				return badUpload(err)
			}
		}
	}
}

var errNoVisit = httpx.Errorf(http.StatusNotFound, "no such visit")

// badUpload turns a failed read of an upload into words for people; other
// errors stay as they are.
func badUpload(err error) error {
	var tooBig *http.MaxBytesError
	var he *httpx.Error
	switch {
	case errors.As(err, &he):
		return err
	case errors.As(err, &tooBig):
		return errTooBig
	case errors.Is(err, multipart.ErrMessageTooLarge), errors.Is(err, io.ErrUnexpectedEOF):
		return httpx.Errorf(http.StatusBadRequest, "the photo didn't arrive whole; try again")
	}
	return fmt.Errorf("photo upload: %w", err)
}

func (h *Handler) deletePhoto(w http.ResponseWriter, r *http.Request) error {
	id, ok := pathID(r, "id")
	photo, ok2 := pathID(r, "photo")
	if !ok || !ok2 {
		return errNoPhoto
	}
	err := h.store.DeletePhoto(r.Context(), space.SpaceFrom(r), id, photo)
	if errors.Is(err, db.ErrNotFound) {
		return errNoPhoto
	} else if err != nil {
		return err
	}
	return h.respond(w, r, http.StatusOK, id)
}

var errNoPhoto = httpx.Errorf(http.StatusNotFound, "no such photo")

// setCover is PUT /api/restaurants/{id}/cover, {"photo_id": n}: one of its
// photos becomes the picture the list shows for it.
func (h *Handler) setCover(w http.ResponseWriter, r *http.Request) error {
	id, ok := pathID(r, "id")
	if !ok {
		return errNoSuch
	}
	var in struct {
		PhotoID int64 `json:"photo_id"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	err := h.store.SetCover(r.Context(), space.SpaceFrom(r), id, in.PhotoID)
	if errors.Is(err, db.ErrNotFound) {
		return errNoPhoto
	} else if err != nil {
		return err
	}
	return h.respond(w, r, http.StatusOK, id)
}

// servePhoto is GET /api/photos/{id} (the original, as taken) and
// GET /api/photos/{id}/thumb.
func (h *Handler) servePhoto(thumb bool) httpx.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		id, ok := pathID(r, "id")
		if !ok {
			return errNoPhoto
		}
		etag := `"` + strconv.FormatInt(id, 10) + map[bool]string{true: "t", false: "o"}[thumb] + `"`
		p, err := h.store.photo(r.Context(), space.SpaceFrom(r), id, thumb)
		if errors.Is(err, db.ErrNotFound) {
			return errNoPhoto
		} else if err != nil {
			return err
		}
		hd := w.Header()
		hd.Set("Cache-Control", photoCache)
		hd.Set("ETag", etag)
		if r.Header.Get("If-None-Match") == etag {
			w.WriteHeader(http.StatusNotModified)
			return nil
		}
		if thumb {
			hd.Set("Content-Type", p.thumbType)
			hd.Set("Content-Length", strconv.Itoa(len(p.thumb)))
			_, _ = w.Write(p.thumb)
			return nil
		}
		hd.Set("Content-Type", p.contentType)
		hd.Set("Content-Length", strconv.FormatInt(p.size, 10))
		hd.Set("Content-Disposition", `inline; filename="photo-`+strconv.FormatInt(id, 10)+extension(p.contentType)+`"`)
		// Headers are out once the first part is written: a failure after
		// that can only cut the answer short, which the client notices.
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
		defer cancel()
		if err := h.store.writeParts(ctx, id, w); err != nil && ctx.Err() == nil {
			slog.WarnContext(ctx, "serve photo", "id", id, "err", err)
		}
		return nil
	}
}

func extension(contentType string) string {
	switch contentType {
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	case "image/gif":
		return ".gif"
	case "image/avif":
		return ".avif"
	}
	return ".jpg"
}

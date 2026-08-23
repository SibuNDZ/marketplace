# Ticket: Notify me when available

**Status:** Open  
**Surfaces:** `ProductCard` out-of-stock overlay (catalog grid)

Out-of-stock cards currently have no waitlist. The button slot should grow a
"Notify me when available" action once transactional email can send the
message. Until then, rendering the control would be a dead click.

Depends on: order/stock events already exist; email infrastructure does not
yet fire a restock notice. Do not ship the button before that slice.

See `ProductCard` TODO(notify-me).

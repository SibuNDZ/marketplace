/**
 * The site's answers to common questions, in ONE place.
 *
 * Consumed by both the /help page and the FAQ widget. That is the point: the
 * widget copying these would create a second set that drifts, and the version
 * a shopper reads would depend on which surface they happened to open.
 *
 * NOTHING HERE MAY BE THE FIRST PLACE A CLAIM APPEARS. Every entry is lifted
 * from a page that already states it — the source is named above each one. An
 * FAQ is a doorway to existing truth, not a new one. If a future question
 * needs an answer the site does not give anywhere, write the real page first,
 * then point an entry at it.
 *
 * Plain data, no JSX: a link is described (`link`), not embedded, so this
 * file stays renderable by any surface and free of component imports.
 */
export type FaqEntry = {
  question: string
  answer: string
  link?: { to: string; label: string }
}

export const FAQ_ENTRIES: FaqEntry[] = [
  // Source: About — "What eRestyu is"
  {
    question: 'Who is eRestyu?',
    answer:
      'A South African multi-vendor marketplace: independent local vendors list their '
      + 'products in one catalog, and shoppers buy from several of them in a single '
      + 'checkout, paying in rand.',
    link: { to: '/about', label: 'More about eRestyu' },
  },
  // Source: Help — "How do I pay?" (also About, "Payments run through…")
  {
    question: 'How do I pay?',
    answer:
      'Checkout runs through our secure payment provider. Your card details are '
      + 'processed by the payment provider directly; eRestyu never sees or stores '
      + 'them. All prices are in South African rand.',
  },
  // Source: Help — "Where is my order?"
  {
    question: 'Where is my order?',
    answer:
      'Your orders page shows every order and its status: pending, paid, shipped, '
      + 'delivered. Shipped orders show a tracking number when the vendor provided '
      + 'one, and you get an email at each step.',
    link: { to: '/orders', label: 'Go to your orders' },
  },
  // Source: Help — "Can I cancel an order?" (also Returns, "Before you pay")
  {
    question: 'Can I cancel an order?',
    answer:
      'Unpaid orders: yes, instantly, from the order page. Paid orders: reply to '
      + 'your confirmation email and we handle it case by case.',
  },
  // Source: Help — "Why did my order expire?"
  {
    question: 'Why did my order expire?',
    answer:
      'Unpaid orders hold stock. If payment does not arrive within 30 minutes, the '
      + 'order cancels itself and the stock goes back on sale.',
  },
  // Source: Help — "What is a delivery fee?" (also Shipping, "Delivery fees")
  {
    question: 'What is a delivery fee?',
    answer:
      'Each vendor charges one flat delivery fee per order, shown as its own line '
      + 'at checkout. Multiple items from the same vendor still cost one fee.',
    link: { to: '/shipping', label: 'Shipping & delivery' },
  },
  // Source: Returns — "After you pay". Deliberately says what actually happens
  // (case by case, while self-service refunds are built) rather than implying
  // a returns process that is not written yet.
  {
    question: "What if something's wrong with my order?",
    answer:
      'Once an order is paid, cancellations and refunds are handled case by case '
      + 'while we build out self-service refunds. Reply to your order confirmation '
      + 'email and we will sort it out with the vendor.',
    link: { to: '/returns', label: 'Returns & cancellations' },
  },
  // Source: Help — "How do I become a vendor?"
  {
    question: 'How do I become a vendor?',
    answer: 'Register an account and start listing.',
    link: { to: '/how-it-works', label: 'How to buy and sell' },
  },
]

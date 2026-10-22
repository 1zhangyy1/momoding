---
name: know-how-travel
description: Where to look for trains, flights and hotels in China and abroad, in what order, which sites tend to wall off a bot and what to do then. Read before searching or comparing train tickets, flights or hotels.
---

# Travel know-how

First read /var/minis/memory/know-how/travel.md if it exists: what was learned on this phone. Where it disagrees with this page, it wins. When a job taught you a better route (which page answered, how to read it, what to skip), add it there as the first line, "- <lesson> (YYYY-MM-DD)": where and how only, never prices, dates or results, which go stale. Browser results also carry this phone's record of each site; a site that kept walling you off is a reason to go elsewhere at once.

## How to work
- Fix the facts first: the exact date (work it out from today), cities and stations or airports, one way or return, how many people. Ask only what would change the answer.
- Read pages, don't photograph them: navigate, then get_readable or a short execute_js that returns only the rows you need. Mobile pages (m.…) are usually lighter.
- Two sources are enough. If the first two you try wall you off (captcha, slider, login, "open the app"), stop searching and say what you have and what the person should check in the app.
- Never build URLs with guessed parameters and keep fixing them; open the site's own search and fill it in, or use a page the record says worked.
- Answer with 2–3 real options: number, times, duration, price, station or airport, and a door-to-door estimate when asked to compare.

## Trains (China)
- 12306 is the source of truth for timetables, seats and prices; its left-ticket query can be read without signing in on the web.
- If it walls you off, a travel site's train pages (Ctrip, Qunar) list the same trains and prices.
- Booking needs the person's 12306 account: never sign in for them; hand over.

## Flights
- Prices move by the hour and aggregators (Ctrip, Qunar, Fliggy) often wall off bots. Try one; if walled, an airline's own site (Air China, China Eastern, China Southern…) or, for international routes, Google Flights.
- For schedules alone (which flights exist, times), flight-status sites (VariFlight / 飞常准) are reliable; say the price must be confirmed in an app.
- Prices: say whether taxes are included.

## Hotels
- Chinese hotel platforms are app-first; read what the web shows, otherwise give names and areas and say prices are in the app. Trip.com or Booking.com for abroad.

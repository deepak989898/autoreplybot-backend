import { DateTime } from "luxon";

/**
 * Cron runs every 15 minutes. Map clock to one of 96 slots per day.
 * @returns {number} 0..95
 */
export function timeSlot(dt) {
  return dt.hour * 4 + Math.floor(dt.minute / 15);
}

/**
 * @param {number} hour 0-23
 * @param {number} minute 0-59
 */
export function scheduleSlot(hour, minute) {
  return hour * 4 + Math.floor(minute / 15);
}

/**
 * @param {string} timezone IANA
 * @param {number} hour
 * @param {number} minute
 * @param {string | undefined} lastPostedDate yyyy-MM-dd in same TZ (optional)
 */
export function shouldPostThisCronWindow(timezone, hour, minute, lastPostedDate) {
  const tz = timezone || process.env.DEFAULT_SCHEDULE_TIMEZONE || "UTC";
  const now = DateTime.now().setZone(tz);
  const todayStr = now.toFormat("yyyy-MM-dd");
  if (lastPostedDate && lastPostedDate === todayStr) {
    return { run: false, todayStr };
  }
  const targetSlot = scheduleSlot(hour, minute);
  const currentSlot = timeSlot(now);
  if (currentSlot !== targetSlot) {
    return { run: false, todayStr };
  }
  return { run: true, todayStr };
}

package utp.edu.pe.api.parking.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class ParkingFeeCalculator {
	public Quote quote(TicketStore.ExitRate rate, Instant exitAt) {
		Instant billableStart = rate.enteredAt().toInstant().plus(Duration.ofMinutes(rate.graceMinutes()));
		long rawMinutes = Math.max(0, Duration.between(billableStart, exitAt).toMinutes());
		int increment = rate.billingIncrementMinutes();
		long billedMinutes = rawMinutes == 0 ? 0 : ((rawMinutes + increment - 1) / increment) * increment;
		Instant billedEnd = billableStart.plus(Duration.ofMinutes(billedMinutes));
		ZoneId zone = ZoneId.of(rate.timeZone());
		Map<String, BigDecimal> breakdown = new LinkedHashMap<>();
		for (Instant minute = billableStart; minute.isBefore(billedEnd); minute = minute.plusSeconds(60)) {
			LocalTime localTime = minute.atZone(zone).toLocalTime();
			boolean night = rate.nightHourlyAmount() != null && isWithinNightPeriod(localTime, rate.nightStartsAt(), rate.nightEndsAt());
			BigDecimal hourlyAmount = night ? rate.nightHourlyAmount() : rate.hourlyAmount();
			String label = night ? "Tarifa nocturna" : "Tarifa ordinaria";
			breakdown.merge(label, hourlyAmount.divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP), BigDecimal::add);
		}
		Map<String, BigDecimal> roundedBreakdown = new LinkedHashMap<>();
		breakdown.forEach((key, value) -> roundedBreakdown.put(key, value.setScale(2, RoundingMode.HALF_UP)));
		BigDecimal amount = roundedBreakdown.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
		return new Quote(rate.ticketId(), rate.code(), rate.licensePlate(), rate.vehicleType(), rate.enteredAt(), exitAt, rawMinutes, billedMinutes, increment, amount, Map.copyOf(roundedBreakdown));
	}

	private boolean isWithinNightPeriod(LocalTime time, LocalTime start, LocalTime end) {
		if (start == null || end == null) return false;
		return start.isBefore(end) ? !time.isBefore(start) && time.isBefore(end) : !time.isBefore(start) || time.isBefore(end);
	}

	public record Quote(String ticketId, String ticketCode, String licensePlate, String vehicleType,
			java.time.OffsetDateTime enteredAt, Instant exitedAt,
			long elapsedMinutes, long billedMinutes, int billingIncrementMinutes,
			BigDecimal amount, Map<String, BigDecimal> breakdown) { }
}

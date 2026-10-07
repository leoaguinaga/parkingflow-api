ALTER TABLE parking_ticket
    ADD COLUMN client_operation_id UUID;

CREATE UNIQUE INDEX uq_parking_ticket_client_operation
    ON parking_ticket (client_operation_id)
    WHERE client_operation_id IS NOT NULL;

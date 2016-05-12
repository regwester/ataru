-- name: add-ticket-query!
-- User logged in, add ticket
insert into cas_ticketstore (ticket) values (:ticket);

-- name: remove-ticket-query!
-- User logged out, remove the ticket
delete from cas_ticketstore where ticket = :ticket;

-- name: ticket-exists-query
-- Check that the ticket exists
select ticket from cas_ticketstore where ticket = :ticket;

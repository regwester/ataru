create index application_events_application_key on application_events (application_key);
create index application_confirmation_emails_application_key on application_confirmation_emails (application_key);
alter table application_reviews add constraint application_reviews_application_key unique (application_key);
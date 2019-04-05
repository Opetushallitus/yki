# Scheduled Tasks

YKI has multiple tasks which are scheduled using Duct module [scheduler.simple](https://github.com/duct-framework/scheduler.simple).
As a general rule all interactions with external systems which user can't retry from UI are implemented using scheduled jobs
which are retried if failed.

## registration-state-handler
Polls started and submitted registrations and sets started registrations to expired after 30 minutes and submitted after 8 days.

## data-sync-queue-reader
Sends new and deleted exam session and organizer data to YKI register.

## participants-sync-handler
Sends participants of open exam sessions to YKI register 3 times a day.

## exam-session-queue-handler
Sends notification emails from exam sessions that have free space to particants that have added themselves to notification queue.
Sends notification only once per day between 8 - 22.

## email-queue-reader
Sends email requests to Ryhmäsähköposti service.





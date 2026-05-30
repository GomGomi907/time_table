delete from calendar_connections
where access_token = 'mock-google-access-token'
   or refresh_token = 'mock-google-refresh-token'
   or google_subject like 'mock-google-id:%';

delete from sync_mappings
where external_id in ('mock-calendar-inbound-event', '@default:mock-tasks-inbound-task')
   or external_etag in ('mock-calendar-etag-1', 'mock-task-etag-1')
   or metadata like '%"mock":true%';

delete from events
where external_source_id in ('google_calendar:mock-calendar-inbound-event')
   or description = '로컬 E2E용 Google Calendar mock inbound event';

delete from tasks
where external_source_id in ('google_tasks:@default:mock-tasks-inbound-task')
   or description = '로컬 E2E용 Google Tasks mock inbound task';

delete from schedule_blocks
where source_type = 'DEFAULT_ROUTINE'
   or source_ref = 'default-routine';

delete from goals
where (title = '영어 공부' and description = '이번 달 영어 공부 20시간 누적')
   or (title = '주 3회 운동' and description = '운동 루틴을 주간 시간표에 안정적으로 배치')
   or (title = '시간표 서비스 MVP' and description = '문서-설계-구현-검증까지 한 주 안에 완료');

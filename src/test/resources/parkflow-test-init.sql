CREATE ROLE parkflow_app LOGIN PASSWORD 'parkflow_app_test';
GRANT CONNECT ON DATABASE parkflow_test TO parkflow_app;
GRANT USAGE ON SCHEMA public TO parkflow_app;

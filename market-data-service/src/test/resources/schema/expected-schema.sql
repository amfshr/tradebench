CREATE TABLE public.bars_1m (
    user_id smallint NOT NULL,
    source_id smallint NOT NULL,
    instrument_id smallint NOT NULL,
    start_utc timestamp with time zone NOT NULL,
    bid_o numeric NOT NULL,
    bid_h numeric NOT NULL,
    bid_l numeric NOT NULL,
    bid_c numeric NOT NULL,
    ask_o numeric NOT NULL,
    ask_h numeric NOT NULL,
    ask_l numeric NOT NULL,
    ask_c numeric NOT NULL,
    ltv bigint
);
CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);
CREATE TABLE public.instruments (
    id smallint NOT NULL,
    epic text NOT NULL,
    name text
);
ALTER TABLE public.instruments ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.instruments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.job_runs (
    id bigint NOT NULL,
    job_name text NOT NULL,
    instance text NOT NULL,
    started_utc timestamp with time zone NOT NULL,
    finished_utc timestamp with time zone,
    status text NOT NULL,
    detail jsonb
);
ALTER TABLE public.job_runs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.job_runs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.service_events (
    id bigint NOT NULL,
    instance text NOT NULL,
    event_type text NOT NULL,
    at_utc timestamp with time zone NOT NULL,
    payload jsonb
);
ALTER TABLE public.service_events ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.service_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.sources (
    id smallint NOT NULL,
    name text NOT NULL
);
ALTER TABLE public.sources ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.sources_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.ticks (
    id bigint NOT NULL,
    user_id smallint NOT NULL,
    source_id smallint NOT NULL,
    instrument_id smallint NOT NULL,
    ts_utc timestamp with time zone NOT NULL,
    bid numeric NOT NULL,
    ask numeric NOT NULL
);
ALTER TABLE public.ticks ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.ticks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.users (
    id smallint NOT NULL,
    name text NOT NULL
);
ALTER TABLE public.users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
ALTER TABLE ONLY public.bars_1m
    ADD CONSTRAINT bars_1m_pkey PRIMARY KEY (user_id, source_id, instrument_id, start_utc);
ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);
ALTER TABLE ONLY public.instruments
    ADD CONSTRAINT instruments_epic_key UNIQUE (epic);
ALTER TABLE ONLY public.instruments
    ADD CONSTRAINT instruments_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.job_runs
    ADD CONSTRAINT job_runs_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.service_events
    ADD CONSTRAINT service_events_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sources
    ADD CONSTRAINT sources_name_key UNIQUE (name);
ALTER TABLE ONLY public.sources
    ADD CONSTRAINT sources_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.ticks
    ADD CONSTRAINT ticks_dedupe UNIQUE (user_id, source_id, instrument_id, ts_utc, bid, ask);
ALTER TABLE ONLY public.ticks
    ADD CONSTRAINT ticks_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_name_key UNIQUE (name);
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);
CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);
CREATE INDEX service_events_type_idx ON public.service_events USING btree (event_type, at_utc);
CREATE INDEX ticks_series_idx ON public.ticks USING btree (instrument_id, source_id, user_id, ts_utc);
ALTER TABLE ONLY public.bars_1m
    ADD CONSTRAINT bars_1m_instrument_id_fkey FOREIGN KEY (instrument_id) REFERENCES public.instruments(id);
ALTER TABLE ONLY public.bars_1m
    ADD CONSTRAINT bars_1m_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.sources(id);
ALTER TABLE ONLY public.bars_1m
    ADD CONSTRAINT bars_1m_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);
ALTER TABLE ONLY public.ticks
    ADD CONSTRAINT ticks_instrument_id_fkey FOREIGN KEY (instrument_id) REFERENCES public.instruments(id);
ALTER TABLE ONLY public.ticks
    ADD CONSTRAINT ticks_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.sources(id);
ALTER TABLE ONLY public.ticks
    ADD CONSTRAINT ticks_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);
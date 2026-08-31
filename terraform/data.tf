# Security groups form a chain: ALB → app → data stores. Each tier only accepts
# traffic from the tier in front of it — least-privilege networking.

resource "aws_security_group" "alb" {
  name_prefix = "${var.project}-alb-"
  vpc_id      = aws_vpc.main.id
  ingress {
    description = "HTTP from internet"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "app" {
  name_prefix = "${var.project}-app-"
  vpc_id      = aws_vpc.main.id
  ingress {
    description     = "App port from ALB only"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "data" {
  name_prefix = "${var.project}-data-"
  vpc_id      = aws_vpc.main.id
  ingress {
    description     = "Postgres from app only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }
  ingress {
    description     = "Redis from app only"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# --- RDS Postgres (managed relational DB) ---
resource "aws_db_subnet_group" "db" {
  name       = "${var.project}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "postgres" {
  identifier             = "${var.project}-db"
  engine                 = "postgres"
  engine_version         = "17"
  instance_class         = "db.t3.micro"
  allocated_storage      = 20
  db_name                = "urlshortener"
  username               = "urls"
  password               = var.db_password
  db_subnet_group_name   = aws_db_subnet_group.db.name
  vpc_security_group_ids = [aws_security_group.data.id]
  multi_az               = false # set true for HA (prod); costs ~2x
  skip_final_snapshot    = true
  publicly_accessible    = false
  backup_retention_period = 1    # required (>0) to allow read replicas
}

# Read replica for the read-heavy redirect path (~100:1 reads:writes). Provisioned here so
# reads can scale out to it while writes go to the primary; wiring app-level read/write
# routing (a DB_READ_URL datasource) is the next step. Toggle with var.enable_read_replica.
resource "aws_db_instance" "postgres_replica" {
  count                  = var.enable_read_replica ? 1 : 0
  identifier             = "${var.project}-db-replica"
  replicate_source_db    = aws_db_instance.postgres.identifier
  instance_class         = "db.t3.micro"
  vpc_security_group_ids = [aws_security_group.data.id]
  skip_final_snapshot    = true
  publicly_accessible    = false
}

# --- ElastiCache Redis (managed cache) ---
resource "aws_elasticache_subnet_group" "redis" {
  name       = "${var.project}-redis"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id           = "${var.project}-redis"
  engine               = "redis"
  node_type            = "cache.t3.micro"
  num_cache_nodes      = 1
  parameter_group_name = "default.redis7"
  subnet_group_name    = aws_elasticache_subnet_group.redis.name
  security_group_ids   = [aws_security_group.data.id]
}

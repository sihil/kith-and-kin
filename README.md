# Wedding website

This is the kith and kin wedding website

## Deployment

There is a cloudformation template that bootstraps an autoscaling group of one etc.

There is a `deploy.sh` script that should build and deploy the application without much downtime. This works by finding the first instance in the ASG and deploying to that. If there is more than one instance then this will break.

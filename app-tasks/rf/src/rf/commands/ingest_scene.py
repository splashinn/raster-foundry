import logging
import os
import subprocess

import boto3
import click
from retrying import retry

from ..utils.io import IngestStatus
from ..models import Scene
from ..ingest.models import Ingest
from ..ingest import (
    create_landsat8_ingest,
    create_sentinel2_ingest,
    create_ingest_definition
)
from ..uploads.landsat8.settings import datasource_id as landsat_id
from ..utils.exception_reporting import wrap_rollbar
from ..utils.emr import get_cluster_id, wait_for_emr_success

logger = logging.getLogger(__name__)

BATCH_JAR_PATH = os.getenv('BATCH_JAR_PATH', 'rf-batch-761c316.jar')
SENTINEL2_DATASOURCE_IDS = ['4a50cb75-815d-4fe5-8bc1-144729ce5b42', 'c33db82d-afdb-43cb-a6ac-ba899e48638d']


@click.command(name='ingest-scene')
@click.argument('scene_id')
@click.option('--ignore-previous', is_flag=True,
              help='Boolean to ignore scene status for ingestion')
@click.option('--local', is_flag=True,
              help='Boolean to run using a spark local cluster rather than EMR job')
@wrap_rollbar
def ingest_scene(scene_id, ignore_previous, local):
    """Ingest a scene into Raster Foundry

    Args:
        scene_id (str): ID of scene to ingest
        ignore_previous (bool): whether to ignore ingest_status when processing ingest
    """
    logger.info("Ingesting Scene: %s", scene_id)
    s3_uri, ingest_definition_id = save_ingest_def_to_s3(scene_id, ignore_previous)
    launch_spark_ingest_job(s3_uri, ingest_definition_id, scene_id, local)
    wait_for_status(ingest_definition_id)


def save_ingest_def_to_s3(scene_id, ignore_previous=False):
    """Create ingest definition and upload to S3

    Args:
        ignore_previous (bool): boolean to ignore previous ingest status
        scene_id (str): ID of scene to create ingest definition for
    """
    scene = Scene.from_id(scene_id)

    logger.info('Beginning to create ingest definition for scene %s for user %s...',
                scene_id, scene.owner)
    if scene.ingestStatus not in [IngestStatus.TOBEINGESTED, IngestStatus.FAILED] and not ignore_previous:
        raise Exception('Scene is no longer waiting to be ingested, error error')

    scene.ingestStatus = IngestStatus.INGESTING
    logger.info('Updating scene (%s) status to ingesting', scene_id)
    scene.update()
    logger.info('Successfully updated scene (%s) status', scene_id)

    logger.info('Creating ingest definition')
    if scene.datasource == landsat_id:
        ingest_definition = create_landsat8_ingest(scene)
    elif scene.datasource in SENTINEL2_DATASOURCE_IDS:
        ingest_definition = create_sentinel2_ingest(scene)
    else:
        ingest_definition = create_ingest_definition(scene)
    ingest_definition.put_in_s3()
    logger.info('Successfully created and pushed ingest definition for scene %s', scene)

    Ingest.delete_status_from_s3(ingest_definition.id)
    return ingest_definition.s3_uri, ingest_definition.id


def launch_spark_ingest_job(ingest_def_uri, ingest_def_id, scene_id, local=False):
    """Launch ingest job and wait for success/failure

    Args:
        ingest_def_uri (str): S3 URI for location of ingest definition
        ingest_def_id (str): ID for ingest definition
        scene_id (str): ID for scene to be ingested
        local (bool): whether to execute ingest as a spark local job

    Returns:
        bool
    """

    logger.info('Launching Spark ingest job with ingest definition %s for scene %s',
                ingest_def_uri, scene_id)
    if local:
        return execute_local_ingest_job(scene_id, ingest_def_uri)
    else:
        cluster_id = get_cluster_id()
        emr_response = execute_ingest_emr_job(scene_id, ingest_def_uri, ingest_def_id, cluster_id)
        logger.info('Launched Spark ingest job for %s with ingest ID %s. Waiting for status changes.',
                    scene_id, ingest_def_id)
        step_id = emr_response['StepIds'][0]
        is_success = wait_for_emr_success(step_id, cluster_id)
        return is_success


def execute_local_ingest_job(scene_id, ingest_s3_uri):
    """Execute ingest job locally with a spark local cluster

    Args:
        scene_id (str): ID of scene to ingest
        ingest_s3_uri (str): S3 location for ingest definition
    """

    ingest_cores = os.getenv('LOCAL_INGEST_CORES', 32)
    ingest_memory = os.getenv('LOCAL_INGEST_MEM_GB', 48)
    command = ['spark-submit',
               '--master', 'local[{}]'.format(ingest_cores),
               '--driver-memory', '{}g'.format(ingest_memory),
               '--class', 'com.azavea.rf.batch.ingest.spark.Ingest',
               '/opt/raster-foundry/jars/rf-batch.jar',
               '-t', '--overwrite', '-s', scene_id, '-j', ingest_s3_uri
    ]
    subprocess.check_call(command)
    logger.info('Finished Ingesting %s in spark local', scene_id)
    return True


@retry(wait_exponential_multiplier=2000, wait_exponential_max=30000, stop_max_attempt_number=5)
def execute_ingest_emr_job(scene_id, ingest_s3_uri, ingest_def_id, cluster_id):
    """Kick off ingest in AWS EMR

    Args:
        scene_id (str): id of the scene to ingest
        ingest_s3_uri (str): URI for ingest definition
        ingest_def_id (str): ID to namespace ingest job
        cluster_id (str): ID of the cluster to submit work to

    Returns:
        dict
    """
    logger.info('Constructing ingest step request for %s for cluster %s for ingest id %s',
                scene_id, cluster_id, ingest_def_id)
    step = {
        'ActionOnFailure': 'CONTINUE',
        'Name': 'ingest-{}'.format(ingest_def_id),
        'HadoopJarStep': {
            'Args': ['/usr/bin/spark-submit',
                     '--master',
                     'yarn',
                     '--deploy-mode',
                     'cluster',
                     '--conf',
                     'spark.yarn.submit.waitAppCompletion=false',
                     '--class',
                     'com.azavea.rf.batch.ingest.spark.Ingest',
                     's3://rasterfoundry-global-artifacts-us-east-1/batch/{}'.format(BATCH_JAR_PATH),
                     '-t',
                     '--overwrite',
                     '-s',
                     scene_id,
                     '-j',
                     ingest_s3_uri],
            'Jar': 's3://us-east-1.elasticmapreduce/libs/script-runner/script-runner.jar'
        }
    }
    logger.info('Submitting step to EMR (%s)', step)
    emr = boto3.client('emr')
    response = emr.add_job_flow_steps(
        JobFlowId=cluster_id,
        Steps=[step]
    )
    logger.info('Received response from EMR API: %s', response)
    return response


@retry(wait_exponential_multiplier=2000, wait_exponential_max=30000, stop_max_attempt_number=2)
def wait_for_status(ingest_def_id):
    """Wait for a result from the Spark job

    Args:
        ingest_def_id (str): ID of ingest definition to check on results for
    """
    ingest_status_dict = Ingest.get_status_from_s3(ingest_def_id)
    scene_id = ingest_status_dict['sceneId']
    status = ingest_status_dict['ingestStatus']

    layer_s3_bucket = os.getenv('TILE_SERVER_BUCKET')
    s3_output_location = 's3://{}/layers'.format(layer_s3_bucket)

    logger.info('Waiting for scene status at %s for scene %s with ingest defintion %s',
                s3_output_location, scene_id, ingest_def_id)

    scene = Scene.from_id(scene_id)
    scene.ingestLocation = s3_output_location
    scene.ingestStatus = status

    if scene.ingestStatus != IngestStatus.FAILED:
        logger.info('Writing scene metadata into postgres.')
        metadata_to_postgres(s3_output_location, scene_id)

    logger.info('Setting scene %s ingest status to %s', scene.id, scene.ingestStatus)
    scene.update()
    logger.info('Successfully updated scene %s\'s ingest status', scene.id)

    notify_for_scene_ingest_status(scene.id)

    if scene.ingestStatus == IngestStatus.FAILED:
        raise Exception('Failed to ingest {} for user {}'.format(scene_id, scene.owner))


def metadata_to_postgres(uri, scene_id):
    """Save metadata of Layer at URI and Scene to Database

    Args:
        uri (str): remote location of layer
        scene_id (str): ID of scene to save metadata for
    """
    bash_cmd = [
        'java', '-cp',
        '/opt/raster-foundry/jars/rf-batch.jar',
        'com.azavea.rf.batch.Main',
        'migration_s3_postgres',
        uri,
        'layer_attributes',
        scene_id
    ]
    logger.debug('Bash command to store metadata: %s', ' '.join(bash_cmd))
    running_cmd = subprocess.Popen(bash_cmd)
    running_cmd.communicate()
    logger.info('Successfully completed metadata postgres write for scene %s', scene_id)
    return True


def notify_for_scene_ingest_status(scene_id):
    """Notify users that are using this scene as well as the scene owner that
    the ingest status of this scene has changed

    Args:
        scene_id (Scene): the scene which has an updated status
    """

    bash_cmd = [
        'java', '-cp',
        '/opt/raster-foundry/jars/rf-batch.jar',
        'com.azavea.rf.batch.Main',
        'notify_ingest_status',
        scene_id
    ]
    running_process = subprocess.Popen(bash_cmd)
    running_process.communicate()
    return True

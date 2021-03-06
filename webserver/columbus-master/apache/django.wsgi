"""
WSGI config for columbus project.

It exposes the WSGI callable as a module-level variable named ``application``.

For more information on this file, see
https://docs.djangoproject.com/en/1.8/howto/deployment/wsgi/
"""

import os
import sys
import socket
import site

# make sure to give permission to all files and folders in virtual env
# set path to the virtual env site packages
#site.addsitedir('/home/prod/venv/lib/python2.7/site-packages')
site.addsitedir('/s/bach/j/under/mroseliu/.local/lib/python2.7/site-packages')
# location of the project and package
#sys.path.append('/home/prod/venv/columbus')
#sys.path.append('/home/prod/venv/columbus/columbus')
sys.path.append('/s/parsons/l/sys/www/radix/columbus-master')
sys.path.append('/s/parsons/l/sys/www/radix/columbus-master/columbus')
if socket.gethostname() == 'Jo_SpectreX':
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "columbus.dev_settings")
elif socket.gethostname() == 'columbus-dev-server':
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "columbus.test_settings")
else:
    os.environ['DJANGO_SETTINGS_MODULE'] = 'columbus.prod_settings'
# activate the virtual environment before serving files from it
#activate_this = '/home/prod/venv/bin/activate_this.py'
#execfile(activate_this, dict(__file__=activate_this))

# import must be done after activating the virtual environment
from django.core.wsgi import get_wsgi_application
application = get_wsgi_application()
#def application(environ, start_response):
#    status = '200 OK'
#    output = 'Hello World!'
#    response_headers = [('Content-type', 'text/plain'),('Content-Length', str(len(output)))]
#    start_response(status, response_headers)
#    return [output]

/**********************************************************************
Copyright (c) 2014 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.maven;

import java.util.List;

import org.codehaus.plexus.util.cli.Commandline;

/**
 * Generates the schema specified by the "schemaName" parameter.
 * @goal schema-createschema
 * @requiresDependencyResolution runtime
 * @description Creates the datastore schema for the specified schemaName.
 */
public class SchemaToolCreateSchemaMojo extends AbstractSchemaToolMojo
{
    private static final String OPERATION_MODE_CREATE = "-createSchema";

    /**
     * {@inheritDoc}
     * @see org.datanucleus.maven.AbstractSchemaToolMojo#prepareModeSpecificCommandLineArguments(org.codehaus.plexus.util.cli.Commandline, java.util.List)
     */
    protected void prepareModeSpecificCommandLineArguments(Commandline cl, List args)
    {
        if (fork)
        {
            cl.createArg().setValue(OPERATION_MODE_CREATE);
            cl.createArg().setValue(schemaName);
        }
        else
        {
            args.add(OPERATION_MODE_CREATE);
            args.add(schemaName);
        }
    }
}
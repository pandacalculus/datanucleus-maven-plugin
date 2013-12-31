/**********************************************************************
Copyright (c) 2005 Rahul Thakur and others. All rights reserved.
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
2007 Andy Jefferson - migrated to JPOX, formatted, etc
    ...
**********************************************************************/
package org.datanucleus.maven;

import java.util.List;

import org.codehaus.plexus.util.cli.Commandline;

/**
 * Validates all database tables required for a set of JDO MetaData files (and classes) for correct structure.
 * @goal schema-validate
 * @requiresDependencyResolution
 */
public class SchemaToolValidateMojo extends AbstractSchemaToolMojo
{
    private static final String OPERATION_MODE_VALIDATE = "-validate";

    /**
     * {@inheritDoc}
     * 
     * @see org.datanucleus.maven.AbstractSchemaToolMojo#prepareModeSpecificCommandLineArguments(org.codehaus.plexus.util.cli.Commandline)
     */
    protected void prepareModeSpecificCommandLineArguments(Commandline cl, List args)
    {
        if (fork)
        {
            cl.createArg().setValue(OPERATION_MODE_VALIDATE);
            if (ddlFile != null && ddlFile.trim().length() > 0)
            {
                cl.createArg().setValue("-ddlFile");
                cl.createArg().setValue(ddlFile);
            }
            if (completeDdl)
            {
                cl.createArg().setValue("-completeDdl");
            }
            if (includeAutoStart)
            {
                cl.createArg().setValue("-includeAutoStart");
            }
        }
        else
        {
            args.add(OPERATION_MODE_VALIDATE);
            if (ddlFile != null && ddlFile.trim().length() > 0)
            {
                args.add("-ddlFile");
                args.add(ddlFile);
            }
            if (completeDdl)
            {
                args.add("-completeDdl");
            }
            if (includeAutoStart)
            {
                args.add("-includeAutoStart");
            }
        }
    }
}
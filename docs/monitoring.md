
# Monitoring your OpenWhisk activity with the OpenWhisk Dashboard

The [OpenWhisk Dashboard](https://{DomainName}/whisk/dashboard/) provides a graphical summary of your activity. Use the dashboard to determine the performance and health of your OpenWhisk actions. 

Click Reload at any time to update the dashboard with the latest activation log data.

## Activity Summary

This view provides a high-level summary of your OpenWhisk environment. Use the **Activity Summary** view to monitor the overall health and performance of your OpenWhisk-enabled service. From the metrics in this view, you can do the following:
* Determine the usage rate of your service's OpenWhisk-enabled actions by viewing the number of times that they were invoked.
* Determine the overall rate of failure across all actions. If you spot an error, you can isolate which services or actions had errors by viewing the **Activity Histogram** view. Isolate the errors themselves by viewing the **Activity Log**.
* Determine how well your actions are performing by viewing the average completion time associated with each action. 

<!-- For tips on improving performance, see troubleshooting? -->

## Activity Timeline

The **Activity Timeline** view displays a vertical bar graph for viewing the activity of past and present actions. Red indicates errors within specific actions. Correlate this view with the **Activity Log** to find more details about errors.

## Activity Histogram

The **Activity Histogram** view displays a horizontal bar graph for viewing the activity of past and present actions. Red indicates errors within specific actions. Correlate this view with the **Activity Log** to find more details about errors.

## Activity Log

This view displays displays a formatted version of the activation log. it shows the details of every activation, but polls once a minute for new activations. Click an action to display a detailed log. 
**Note**: To get the output displayed in the Activity Log by using CLI, use the following command: 

  ```
  $ wsk activation poll
  ```

## Filtering options

Select which action log you want to view, and select the time frame of the activity logged. 

**Note**: These filters are applied to all views on the dashboard.
